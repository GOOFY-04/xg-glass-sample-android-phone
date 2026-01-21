package com.universalglasses.device.frame.embedded

import android.content.Context
import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.CapturedImage
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.GlassesError
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesModel
import com.universalglasses.device.frame.flutter.FrameFlutterBridge
import com.universalglasses.device.frame.flutter.FrameFlutterChannelContract
import com.universalglasses.device.frame.flutter.FrameFlutterState
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugins.GeneratedPluginRegistrant
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SDK-owned FlutterEngine + MethodChannel bridge.
 *
 * This removes the need for app developers to manually implement FrameFlutterBridge.
 * The only requirement is that the SDK ships with the embedded Flutter runtime/module
 * (today via included :flutter project; later via prebuilt AAR publication).
 */
class EmbeddedFrameFlutterBridge(
    private val context: Context,
) : FrameFlutterBridge {

    private val _state = MutableStateFlow<FrameFlutterState>(FrameFlutterState.Disconnected)
    override val state: StateFlow<FrameFlutterState> = _state

    private val _events = MutableSharedFlow<GlassesEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<GlassesEvent> = _events

    private val engine: FlutterEngine
    private val channel: MethodChannel

    init {
        val loader = FlutterInjector.instance().flutterLoader()
        if (!loader.initialized()) {
            loader.startInitialization(context)
        }
        loader.ensureInitializationComplete(context, null)

        engine = FlutterEngine(context)
        // register plugins (frame_ble depends on flutter_blue_plus, etc.)
        GeneratedPluginRegistrant.registerWith(engine)

        val entrypoint = DartExecutor.DartEntrypoint(loader.findAppBundlePath(), "main")
        engine.dartExecutor.executeDartEntrypoint(entrypoint)

        channel = MethodChannel(engine.dartExecutor.binaryMessenger, FrameFlutterChannelContract.METHOD_CHANNEL)
        channel.setMethodCallHandler(::onFlutterCall)
    }

    private fun onFlutterCall(call: MethodCall, result: MethodChannel.Result) {
        if (call.method != FrameFlutterChannelContract.Methods.ON_EVENT) {
            result.notImplemented()
            return
        }
        val payload = call.arguments as? Map<*, *> ?: run {
            result.success(null)
            return
        }

        val type = payload[FrameFlutterChannelContract.Events.TYPE]?.toString().orEmpty()
        when (type) {
            FrameFlutterChannelContract.Events.TYPE_STATE -> {
                val v = payload[FrameFlutterChannelContract.Events.STATE_VALUE]?.toString().orEmpty()
                val err = payload[FrameFlutterChannelContract.Events.STATE_ERROR]?.toString()
                _state.value = when (v) {
                    "connecting" -> FrameFlutterState.Connecting
                    "connected" -> FrameFlutterState.Connected
                    "error" -> FrameFlutterState.Error(err ?: "unknown")
                    else -> FrameFlutterState.Disconnected
                }
            }
            FrameFlutterChannelContract.Events.TYPE_LOG -> {
                val msg = payload[FrameFlutterChannelContract.Events.MESSAGE]?.toString().orEmpty()
                _events.tryEmit(GlassesEvent.Log(msg))
            }
            FrameFlutterChannelContract.Events.TYPE_WARNING -> {
                val msg = payload[FrameFlutterChannelContract.Events.MESSAGE]?.toString().orEmpty()
                _events.tryEmit(GlassesEvent.Warning(msg))
            }
            FrameFlutterChannelContract.Events.TYPE_TAP -> {
                val count = (payload[FrameFlutterChannelContract.Events.TAP_COUNT] as? Number)?.toInt() ?: 0
                _events.tryEmit(GlassesEvent.Tap(count))
            }
        }

        result.success(null)
    }

    override suspend fun connect(): Result<Unit> {
        _state.value = FrameFlutterState.Connecting
        return invoke<Boolean>(FrameFlutterChannelContract.Methods.CONNECT, null).map { Unit }
    }

    override suspend fun disconnect() {
        invoke<Boolean>(FrameFlutterChannelContract.Methods.DISCONNECT, null)
        _state.value = FrameFlutterState.Disconnected
        engine.destroy()
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        val args = mapOf(
            FrameFlutterChannelContract.Args.QUALITY to options.quality,
            FrameFlutterChannelContract.Args.TARGET_WIDTH to options.targetWidth,
            FrameFlutterChannelContract.Args.TARGET_HEIGHT to options.targetHeight,
            FrameFlutterChannelContract.Args.TIMEOUT_MS to options.timeoutMs,
        )
        val bytesRes = invoke<ByteArray>(FrameFlutterChannelContract.Methods.CAPTURE_PHOTO, args)
        return bytesRes.map { bytes ->
            CapturedImage(
                jpegBytes = bytes,
                width = null,
                height = null,
                rotationDegrees = null,
                sourceModel = GlassesModel.FRAME,
            )
        }
    }

    override suspend fun displayText(text: String, options: DisplayOptions): Result<Unit> {
        val args = mapOf(
            FrameFlutterChannelContract.Args.TEXT to text,
            FrameFlutterChannelContract.Args.FORCE to options.force,
            FrameFlutterChannelContract.Args.MODE to when (options.mode.name.lowercase()) {
                "append" -> "append"
                else -> "replace"
            }
        )
        return invoke<Boolean>(FrameFlutterChannelContract.Methods.DISPLAY_TEXT, args).map { Unit }
    }

    private suspend fun <T> invoke(method: String, args: Any?): Result<T> {
        return try {
            val out: T = suspendCancellableCoroutine { cont ->
                channel.invokeMethod(method, args, object : MethodChannel.Result {
                    override fun success(result: Any?) {
                        @Suppress("UNCHECKED_CAST")
                        cont.resume(result as T)
                    }

                    override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                        cont.resumeWithException(GlassesError.Transport("Flutter error($errorCode): ${errorMessage ?: ""}"))
                    }

                    override fun notImplemented() {
                        cont.resumeWithException(GlassesError.Unsupported("Flutter method not implemented: $method"))
                    }
                })
            }
            Result.success(out)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}


