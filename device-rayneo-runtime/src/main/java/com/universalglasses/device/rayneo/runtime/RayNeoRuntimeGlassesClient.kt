package com.universalglasses.device.rayneo.runtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.widget.Toast
import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.CapturedImage
import com.universalglasses.core.ConnectionState
import com.universalglasses.core.DeviceCapabilities
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.GlassesClient
import com.universalglasses.core.GlassesError
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * RayNeo (on-glasses) client.
 *
 * This runs inside the RayNeo glasses app process.
 * - `capturePhoto()` uses Camera2 to capture a single JPEG (no preview UI).
 * - `display()` uses a pluggable sink; default is a Toast.
 *
 * Note: This module is intentionally vendor-SDK-free. If you want to integrate with RayNeo Mercury
 * SDK UI components, implement a custom [RayNeoDisplaySink] and/or your own capture pipeline.
 */
class RayNeoRuntimeGlassesClient(
    private val context: Context,
    private val displaySink: RayNeoDisplaySink = ToastDisplaySink(),
) : GlassesClient {

    override val model: GlassesModel = GlassesModel.RAYNEO

    override val capabilities: DeviceCapabilities = DeviceCapabilities(
        canCapturePhoto = true,
        canDisplayText = true,
        supportsTapEvents = false,
        supportsStreamingTextUpdates = false,
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 64)
    override val events: Flow<GlassesEvent> = _events

    private val captureMutex = Mutex()

    override suspend fun connect(): Result<Unit> {
        _state.value = ConnectionState.Connected
        return Result.success(Unit)
    }

    override suspend fun disconnect() {
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)
        if (!hasCameraPermission()) return Result.failure(GlassesError.PermissionDenied)

        return try {
            captureMutex.withLock {
            val timeoutMs = options.timeoutMs
            val width = (options.targetWidth ?: 1920).coerceIn(320, 3840)
            val height = (options.targetHeight ?: 1080).coerceIn(240, 2160)

            val jpeg = withTimeoutOrNull(timeoutMs) {
                captureJpegOnce(width, height)
            } ?: return Result.failure(GlassesError.Timeout("capturePhoto"))

            return Result.success(
                CapturedImage(
                    jpegBytes = jpeg,
                    width = width,
                    height = height,
                    rotationDegrees = null,
                    sourceModel = GlassesModel.RAYNEO,
                )
            )
            }
        } catch (e: Exception) {
            Result.failure(GlassesError.Transport("RayNeo capture failed: ${e.message ?: e::class.java.simpleName}", e))
        }
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        if (_state.value !is ConnectionState.Connected) return Result.failure(GlassesError.NotConnected)
        return try {
            displaySink.display(context, text, options)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(GlassesError.Transport("RayNeo display failed: ${e.message ?: e::class.java.simpleName}", e))
        }
    }

    private fun hasCameraPermission(): Boolean {
        return context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun captureJpegOnce(width: Int, height: Int): ByteArray {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = chooseCameraId(cameraManager)
            ?: throw GlassesError.Transport("No camera available")

        val thread = HandlerThread("rayneo-camera").apply { start() }
        val handler = Handler(thread.looper)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null

        reader = ImageReader.newInstance(width, height, android.graphics.ImageFormat.JPEG, 2)

        return suspendCancellableCoroutine { cont ->
            fun cleanup() {
                try {
                    session?.close()
                } catch (_: Exception) {}
                try {
                    device?.close()
                } catch (_: Exception) {}
                try {
                    reader?.close()
                } catch (_: Exception) {}
                try {
                    thread.quitSafely()
                } catch (_: Exception) {}
            }

            cont.invokeOnCancellation { cleanup() }

            reader.setOnImageAvailableListener({ r ->
                if (!cont.isActive) return@setOnImageAvailableListener
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    cont.resume(bytes)
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                } finally {
                    image.close()
                    cleanup()
                }
            }, handler)

            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    camera.createCaptureSession(
                        listOf(reader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    }
                                    s.capture(req.build(), null, handler)
                                } catch (e: Exception) {
                                    if (cont.isActive) cont.resumeWithException(e)
                                    cleanup()
                                }
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                if (cont.isActive) cont.resumeWithException(GlassesError.Transport("Camera session configure failed"))
                                cleanup()
                            }
                        },
                        handler
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {
                    if (cont.isActive) cont.resumeWithException(GlassesError.Transport("Camera disconnected"))
                    cleanup()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    if (cont.isActive) cont.resumeWithException(GlassesError.Transport("Camera error: $error"))
                    cleanup()
                }
            }, handler)
        }
    }

    private fun chooseCameraId(cameraManager: CameraManager): String? {
        val ids = cameraManager.cameraIdList
        if (ids.isEmpty()) return null

        // Prefer a back-facing camera if available.
        for (id in ids) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return ids.firstOrNull()
    }
}

fun interface RayNeoDisplaySink {
    suspend fun display(context: Context, text: String, options: DisplayOptions)
}

class ToastDisplaySink : RayNeoDisplaySink {
    override suspend fun display(context: Context, text: String, options: DisplayOptions) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
}


