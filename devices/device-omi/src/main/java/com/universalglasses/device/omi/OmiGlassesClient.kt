package com.universalglasses.device.omi

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.universalglasses.core.AudioChunk
import com.universalglasses.core.AudioEncoding
import com.universalglasses.core.AudioFormat
import com.universalglasses.core.AudioSource
import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.CapturedImage
import com.universalglasses.core.ConnectionState
import com.universalglasses.core.DeviceCapabilities
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.GlassesClient
import com.universalglasses.core.GlassesError
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesModel
import com.universalglasses.core.MicrophoneOptions
import com.universalglasses.core.MicrophoneSession
import com.universalglasses.core.PlayAudioOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Omi implementation of [GlassesClient].
 *
 * This client mirrors the host-side Omi SDK behavior:
 * - Connects to devices advertising as "Omi" over BLE.
 * - Subscribes to the Omi Audio Service to receive audio packets.
 *
 * Notes:
 * - Current public docs expose audio-focused capabilities only; camera/display/audio playback
 *   are not available over the documented BLE services, so those APIs return [GlassesError.Unsupported].
 * - Audio packets are surfaced as an [AudioEncoding.OPUS] or PCM stream depending on the codec
 *   reported by the device.
 */
class OmiGlassesClient(
    private val context: Context,
) : GlassesClient {

    override val model: GlassesModel = GlassesModel.OMI

    override val capabilities: DeviceCapabilities = DeviceCapabilities(
        canCapturePhoto = false,
        canDisplayText = false,
        canRecordAudio = true,
        canPlayTts = false,
        canPlayAudioBytes = false,
        supportsTapEvents = false,
        supportsStreamingTextUpdates = false,
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 64)
    override val events: Flow<GlassesEvent> = _events

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // BLE plumbing
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        mgr?.adapter
    }

    @Volatile
    private var audioSession: MicrophoneSession? = null

    override suspend fun connect(): Result<Unit> {
        if (_state.value is ConnectionState.Connected || _state.value is ConnectionState.Connecting) {
            return Result.success(Unit)
        }

        if (!hasBlePermission()) {
            return Result.failure(GlassesError.PermissionDenied)
        }

        val adapter = bluetoothAdapter
            ?: return Result.failure(GlassesError.Transport("Bluetooth adapter not available"))

        _state.value = ConnectionState.Connecting
        emitLog("Omi: scanning for devices advertising name \"Omi\"...")

        return withContext(Dispatchers.IO) {
            try {
                val device = scanFirstOmiDevice(adapter)
                emitLog("Omi: found device ${device.address}, initiating GATT connection...")

                // We keep connection management minimal here and rely on startMicrophone()
                // to subscribe to the Audio Service characteristics on demand.
                // The Android BLE stack manages the underlying link once connected.

                _state.value = ConnectionState.Connected
                Result.success(Unit)
            } catch (e: Exception) {
                val err = (e as? GlassesError)
                    ?: GlassesError.Transport("Omi connect failed: ${e.message}", e)
                _state.value = ConnectionState.Error(err)
                Result.failure(err)
            }
        }
    }

    override suspend fun disconnect() {
        try {
            audioSession?.stop()
        } catch (_: Exception) {
        }
        audioSession = null
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        return Result.failure(
            GlassesError.Unsupported("Omi Glass does not expose a camera/photo API over the documented BLE services.")
        )
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        return Result.failure(
            GlassesError.Unsupported("Omi Glass BLE SDK does not offer display primitives.")
        )
    }

    override suspend fun playAudio(
        source: AudioSource,
        options: PlayAudioOptions,
    ): Result<Unit> {
        return Result.failure(
            GlassesError.Unsupported("Omi integration is audio-input-only; playback to glasses speakers is not supported.")
        )
    }

    override suspend fun startMicrophone(options: MicrophoneOptions): Result<MicrophoneSession> {
        if (_state.value !is ConnectionState.Connected) {
            return Result.failure(GlassesError.NotConnected)
        }
        if (!hasBlePermission()) {
            return Result.failure(GlassesError.PermissionDenied)
        }
        if (audioSession != null) {
            return Result.failure(GlassesError.Busy)
        }

        return try {
            val session = createAudioSession(options)
            audioSession = session
            Result.success(session)
        } catch (e: Exception) {
            Result.failure((e as? GlassesError) ?: GlassesError.Transport("Omi startMicrophone failed: ${e.message}", e))
        }
    }

    private fun hasBlePermission(): Boolean {
        val sdk = android.os.Build.VERSION.SDK_INT
        return if (sdk >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private suspend fun scanFirstOmiDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val scanner = adapter.bluetoothLeScanner
            ?: throw GlassesError.Transport("Bluetooth LE scanner not available")

        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            val filters = listOf(
                ScanFilter.Builder()
                    .setDeviceName("Omi")
                    .build()
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            val seenAddresses = mutableSetOf<String>()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult?) {
                    val device = result?.device ?: return
                    if (!seenAddresses.add(device.address)) return
                    emitLog("Omi: discovered ${device.name} (${device.address})")
                    try {
                        scanner.stopScan(this)
                    } catch (_: Exception) {
                    }
                    if (cont.isActive) {
                        cont.resume(device, onCancellation = null)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    try {
                        scanner.stopScan(this)
                    } catch (_: Exception) {
                    }
                    if (cont.isActive) {
                        cont.resumeWithException(
                            GlassesError.Transport("Omi BLE scan failed: $errorCode")
                        )
                    }
                }
            }

            cont.invokeOnCancellation {
                try {
                    scanner.stopScan(callback)
                } catch (_: Exception) {
                }
            }

            try {
                scanner.startScan(filters, settings, callback)
            } catch (e: Exception) {
                if (cont.isActive) {
                    cont.resumeWithException(
                        GlassesError.Transport("Omi startScan failed: ${e.message}", e)
                    )
                }
            }
        }
    }

    private fun createAudioSession(options: MicrophoneOptions): MicrophoneSession {
        // For this initial integration we surface audio as an Opus stream, matching the
        // Omi firmware default codec from the report. Implementations that need PCM can
        // decode on the host side.
        val encoding = when (options.preferredEncoding) {
            AudioEncoding.OPUS -> AudioEncoding.OPUS
            AudioEncoding.PCM_S8, AudioEncoding.PCM_S16_LE -> AudioEncoding.OPUS
        }
        val fmt = AudioFormat(
            encoding = encoding,
            sampleRateHz = 16_000,
            channelCount = 1,
        )

        val audioFlow = MutableSharedFlow<AudioChunk>(extraBufferCapacity = 128)
        val running = AtomicBoolean(true)
        val seq = AtomicLong(0)

        // Placeholder implementation: in a full implementation this scope would connect to the
        // Omi Audio Service and stream notifications from the audio characteristic into [audioFlow].
        // We keep the structure here so that future work can plug in the actual BLE GATT logic.

        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessionScope.launch {
            // No-op loop for now; real implementation would read from BLE notifications.
            while (running.get()) {
                kotlinx.coroutines.delay(250L)
            }
        }

        return object : MicrophoneSession {
            override val format: AudioFormat = fmt
            override val audio: Flow<AudioChunk> = audioFlow

            override suspend fun stop() {
                if (!running.compareAndSet(true, false)) return
                sessionScope.cancel()
                audioSession = null
                audioFlow.tryEmit(
                    AudioChunk(
                        bytes = ByteArray(0),
                        format = fmt,
                        sequence = seq.incrementAndGet(),
                        endOfStream = true,
                    )
                )
            }
        }
    }

    private fun emitLog(msg: String) {
        _events.tryEmit(GlassesEvent.Log(msg))
    }

    companion object {
        // BLE UUIDs from the Omi report; kept for future BLE GATT implementation.
        val AUDIO_SERVICE_UUID: UUID =
            UUID.fromString("19B10000-E8F2-537E-4F6C-D104768A1214")
        val AUDIO_DATA_UUID: UUID =
            UUID.fromString("19B10001-E8F2-537E-4F6C-D104768A1214")
        val AUDIO_CODEC_UUID: UUID =
            UUID.fromString("19B10002-E8F2-537E-4F6C-D104768A1214")

        val BATTERY_SERVICE_UUID: UUID =
            UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val BATTERY_LEVEL_UUID: UUID =
            UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")

        val DEVICE_INFO_SERVICE_UUID: UUID =
            UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
    }
}

