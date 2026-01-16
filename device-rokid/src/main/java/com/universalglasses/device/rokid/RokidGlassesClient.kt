package com.universalglasses.device.rokid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Environment
import android.os.ParcelUuid
import androidx.appcompat.app.AppCompatActivity
import com.rokid.cxr.client.extend.CxrApi
import com.rokid.cxr.client.extend.callbacks.BluetoothStatusCallback
import com.rokid.cxr.client.extend.callbacks.PhotoPathCallback
import com.rokid.cxr.client.extend.callbacks.SyncStatusCallback
import com.rokid.cxr.client.extend.callbacks.WifiP2PStatusCallback
import com.rokid.cxr.client.utils.ValueUtil
import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.CapturedImage
import com.universalglasses.core.ConnectionState
import com.universalglasses.core.DeviceCapabilities
import com.universalglasses.core.DisplayMode
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.GlassesClient
import com.universalglasses.core.GlassesError
import com.universalglasses.core.GlassesEvent
import com.universalglasses.core.GlassesModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Rokid implementation of [GlassesClient].
 *
 * Internals:
 * - Bluetooth: scan -> initBluetooth() -> connectBluetooth()
 * - Wi‑Fi P2P: initWifiP2P() is required before syncSingleFile()
 * - Photo: takeGlassPhoto() returns remote path -> syncSingleFile() gives local absolute path -> readBytes()
 *
 * Notes:
 * - This SDK does NOT request runtime permissions; the host app must handle permissions.
 */
class RokidGlassesClient(
    private val activity: AppCompatActivity,
    private val options: RokidOptions = RokidOptions(),
) : GlassesClient {

    override val model: GlassesModel = GlassesModel.ROKID
    override val capabilities: DeviceCapabilities = DeviceCapabilities(
        canCapturePhoto = true,
        canDisplayText = true,
        supportsTapEvents = false,
        supportsStreamingTextUpdates = true, // custom view supports frequent updates; adapter still throttles
    )

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state: StateFlow<ConnectionState> = _state

    private val _events = MutableSharedFlow<GlassesEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<GlassesEvent> = _events

    private val display = RokidDisplayController()

    private val prefs by lazy { activity.getSharedPreferences(PREFS_BT, Context.MODE_PRIVATE) }

    private val scanResultMap: ConcurrentHashMap<String, BluetoothDevice> = ConcurrentHashMap()
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val mgr = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        mgr.adapter
    }

    @Volatile private var wifiReady: Boolean = false
    @Volatile private var btReady: Boolean = false

    override suspend fun connect(): Result<Unit> {
        if (_state.value is ConnectionState.Connected || _state.value is ConnectionState.Connecting) {
            return Result.success(Unit)
        }
        _state.value = ConnectionState.Connecting
        emitLog("Rokid: connecting (BT + Wi‑Fi P2P)...")

        return try {
            withTimeout(options.connectTimeoutMs) {
                // 1) Bluetooth
                ensureBluetoothConnected()
                // 2) Wi‑Fi P2P
                ensureWifiP2pConnected()
            }
            _state.value = ConnectionState.Connected
            Result.success(Unit)
        } catch (ce: CancellationException) {
            _state.value = ConnectionState.Disconnected
            Result.failure(ce)
        } catch (e: Exception) {
            val err = (e as? GlassesError) ?: GlassesError.Transport("Rokid connect failed: ${e.message}", e)
            _state.value = ConnectionState.Error(err)
            Result.failure(err)
        }
    }

    override suspend fun disconnect() {
        emitLog("Rokid: disconnecting...")
        try {
            CxrApi.getInstance().deinitWifiP2P()
        } catch (_: Exception) {}
        try {
            CxrApi.getInstance().deinitBluetooth()
        } catch (_: Exception) {}
        try {
            stopScan()
        } catch (_: Exception) {}
        try {
            display.close()
        } catch (_: Exception) {}
        wifiReady = false
        btReady = false
        _state.value = ConnectionState.Disconnected
    }

    override suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage> {
        if (_state.value !is ConnectionState.Connected || !btReady || !wifiReady) {
            return Result.failure(GlassesError.NotConnected)
        }

        val quality = (options.quality ?: this.options.defaultJpegQuality).coerceIn(1, 100)
        val width = options.targetWidth ?: this.options.defaultWidth
        val height = options.targetHeight ?: this.options.defaultHeight

        return try {
            val bytes = withTimeout(options.timeoutMs) {
                val remotePath = takeGlassPhotoSuspend(width, height, quality)
                val localPath = syncSingleFileSuspend(remotePath)
                File(localPath).readBytes()
            }
            Result.success(
                CapturedImage(
                    jpegBytes = bytes,
                    width = width,
                    height = height,
                    rotationDegrees = null,
                    sourceModel = GlassesModel.ROKID,
                )
            )
        } catch (e: Exception) {
            val err = (e as? GlassesError) ?: GlassesError.Transport("Rokid capture failed: ${e.message}", e)
            Result.failure(err)
        }
    }

    override suspend fun display(text: String, options: DisplayOptions): Result<Unit> {
        if (_state.value !is ConnectionState.Connected || !btReady) {
            return Result.failure(GlassesError.NotConnected)
        }
        val finalText = when (options.mode) {
            DisplayMode.REPLACE -> text
            DisplayMode.APPEND -> display.lastText + text
        }
        return try {
            withContext(Dispatchers.Main) {
                display.showText(finalText, force = options.force)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(GlassesError.Transport("Rokid display failed: ${e.message}", e))
        }
    }

    // -----------------------
    // Bluetooth + Wi‑Fi P2P
    // -----------------------

    private suspend fun ensureBluetoothConnected() {
        // Prefer reconnect if we have cached info.
        val socketUuid = prefs.getString(PREF_KEY_SOCKET_UUID, null)?.trim().orEmpty()
        val macAddress = prefs.getString(PREF_KEY_MAC_ADDRESS, null)?.trim().orEmpty()
        if (socketUuid.isNotBlank() && macAddress.isNotBlank()) {
            emitLog("Rokid: trying BT reconnect...")
            try {
                connectBluetoothSuspend(socketUuid, macAddress, useApplicationContext = true)
                btReady = true
                return
            } catch (e: Exception) {
                // Common in practice: cached reconnect info becomes stale after re-pair/reset/firmware changes.
                // Fall back to scan+init flow automatically.
                emitWarn("Rokid: BT reconnect failed, falling back to scan/init: ${e.message}")
                clearReconnectInfo()
            }
        }

        emitLog("Rokid: scanning for device...")
        val device = scanFirstDeviceSuspend()
        emitLog("Rokid: initBluetooth for ${device.address}")
        val (uuid, mac) = initBluetoothSuspend(device)
        saveReconnectInfo(uuid, mac)
        emitLog("Rokid: connectBluetooth...")
        connectBluetoothSuspend(uuid, mac, useApplicationContext = false)
        btReady = true
    }

    private suspend fun ensureWifiP2pConnected() {
        if (wifiReady) return

        emitLog("Rokid: initWifiP2P...")
        suspendCancellableCoroutine<Unit> { cont ->
            var completed = false
            val status = CxrApi.getInstance().initWifiP2P(object : WifiP2PStatusCallback {
                override fun onConnected() {
                    wifiReady = true
                    emitLog("Rokid: Wi‑Fi P2P connected")
                    if (!completed) {
                        completed = true
                        cont.resume(Unit)
                    }
                }

                override fun onDisconnected() {
                    wifiReady = false
                    emitWarn("Rokid: Wi‑Fi P2P disconnected")
                }

                override fun onFailed(errorCode: ValueUtil.CxrWifiErrorCode?) {
                    wifiReady = false
                    emitWarn("Rokid: Wi‑Fi P2P init failed: $errorCode")
                    if (!completed) {
                        completed = true
                        cont.resumeWithException(GlassesError.Transport("Rokid initWifiP2P failed: $errorCode"))
                    }
                }
            })

            if (status == ValueUtil.CxrStatus.REQUEST_FAILED && !completed) {
                completed = true
                cont.resumeWithException(GlassesError.Transport("Rokid initWifiP2P REQUEST_FAILED"))
            }
        }
    }

    // -----------------------
    // Photo capture + sync
    // -----------------------

    private suspend fun takeGlassPhotoSuspend(width: Int, height: Int, quality: Int): String =
        suspendCoroutine { cont ->
            val status = CxrApi.getInstance().takeGlassPhoto(width, height, quality, object : PhotoPathCallback {
                override fun onPhotoPath(status: ValueUtil.CxrStatus?, path: String?) {
                    if (status == ValueUtil.CxrStatus.RESPONSE_SUCCEED && !path.isNullOrBlank()) {
                        cont.resume(path)
                    } else {
                        cont.resumeWithException(
                            GlassesError.Transport("Rokid takeGlassPhoto failed: status=$status path=$path")
                        )
                    }
                }
            })
            if (status == ValueUtil.CxrStatus.REQUEST_FAILED) {
                cont.resumeWithException(GlassesError.Transport("Rokid takeGlassPhoto REQUEST_FAILED"))
            }
        }

    private suspend fun syncSingleFileSuspend(remotePath: String): String =
        suspendCoroutine { cont ->
            val saveDir = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: run {
                    cont.resumeWithException(GlassesError.Transport("No external pictures dir"))
                    return@suspendCoroutine
                }

            // CXR-M concatenates with string addition internally; always include trailing "/"
            val savePath = saveDir.absolutePath + File.separator

            val ok = CxrApi.getInstance().syncSingleFile(
                savePath,
                ValueUtil.CxrMediaType.PICTURE,
                remotePath,
                object : SyncStatusCallback {
                    override fun onSyncStart() = Unit
                    override fun onSingleFileSynced(fileName: String?) {
                        if (fileName.isNullOrBlank()) {
                            cont.resumeWithException(GlassesError.Transport("syncSingleFile returned empty fileName"))
                            return
                        }
                        cont.resume(fileName)
                    }

                    override fun onSyncFailed() {
                        cont.resumeWithException(GlassesError.Transport("syncSingleFile failed"))
                    }

                    override fun onSyncFinished() = Unit
                }
            )

            if (!ok) {
                cont.resumeWithException(GlassesError.Transport("syncSingleFile request failed (returned false)"))
            }
        }

    // -----------------------
    // BLE scan + connect
    // -----------------------

    @SuppressLint("MissingPermission")
    private suspend fun scanFirstDeviceSuspend(): BluetoothDevice = suspendCoroutine { cont ->
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            cont.resumeWithException(GlassesError.Transport("Bluetooth LE scanner not available"))
            return@suspendCoroutine
        }

        scanResultMap.clear()

        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(ROKID_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder().build()

        var completed = false
        lateinit var cb: ScanCallback
        cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                if (!scanResultMap.containsKey(device.address)) {
                    scanResultMap[device.address] = device
                    stopScan(cb)
                    if (!completed) {
                        completed = true
                        cont.resume(device)
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                stopScan(cb)
                if (!completed) {
                    completed = true
                    cont.resumeWithException(GlassesError.Transport("BLE scan failed: $errorCode"))
                }
            }
        }

        try {
            scanner.startScan(filters, settings, cb)
        } catch (e: Exception) {
            if (!completed) {
                completed = true
                cont.resumeWithException(GlassesError.Transport("startScan failed: ${e.message}", e))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan(callback: ScanCallback? = null) {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        try {
            if (callback != null) scanner.stopScan(callback)
        } catch (_: Exception) {}
    }

    private suspend fun initBluetoothSuspend(device: BluetoothDevice): Pair<String, String> =
        suspendCoroutine { cont ->
            CxrApi.getInstance().initBluetooth(activity, device, object : BluetoothStatusCallback {
                override fun onConnectionInfo(
                    socketUuid: String?,
                    macAddress: String?,
                    rokidAccount: String?,
                    glassesType: Int
                ) {
                    if (!socketUuid.isNullOrBlank() && !macAddress.isNullOrBlank()) {
                        cont.resume(socketUuid to macAddress)
                    } else {
                        cont.resumeWithException(GlassesError.Transport("onConnectionInfo missing uuid/mac"))
                    }
                }

                override fun onConnected() = Unit
                override fun onDisconnected() = Unit

                override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
                    cont.resumeWithException(GlassesError.Transport("initBluetooth failed: $errorCode"))
                }
            })
        }

    private suspend fun connectBluetoothSuspend(socketUuid: String, macAddress: String, useApplicationContext: Boolean) {
        suspendCoroutine<Unit> { cont ->
            val ctx = if (useApplicationContext) activity.applicationContext else activity
            CxrApi.getInstance().connectBluetooth(ctx, socketUuid, macAddress, object : BluetoothStatusCallback {
                override fun onConnectionInfo(
                    socketUuid: String?,
                    macAddress: String?,
                    rokidAccount: String?,
                    glassesType: Int
                ) = Unit

                override fun onConnected() {
                    cont.resume(Unit)
                }

                override fun onDisconnected() {
                    cont.resumeWithException(GlassesError.Transport("connectBluetooth disconnected"))
                }

                override fun onFailed(errorCode: ValueUtil.CxrBluetoothErrorCode?) {
                    cont.resumeWithException(GlassesError.Transport("connectBluetooth failed: $errorCode"))
                }
            })
        }
    }

    private fun saveReconnectInfo(socketUuid: String, macAddress: String) {
        prefs.edit()
            .putString(PREF_KEY_SOCKET_UUID, socketUuid)
            .putString(PREF_KEY_MAC_ADDRESS, macAddress)
            .apply()
    }

    private fun clearReconnectInfo() {
        prefs.edit()
            .remove(PREF_KEY_SOCKET_UUID)
            .remove(PREF_KEY_MAC_ADDRESS)
            .apply()
    }

    private fun emitLog(msg: String) {
        _events.tryEmit(GlassesEvent.Log(msg))
    }

    private fun emitWarn(msg: String) {
        _events.tryEmit(GlassesEvent.Warning(msg))
    }

    data class RokidOptions(
        val connectTimeoutMs: Long = 30_000,
        val defaultWidth: Int = 2400,
        val defaultHeight: Int = 1800,
        val defaultJpegQuality: Int = 90,
    )

    private companion object {
        const val ROKID_SERVICE_UUID = "00009100-0000-1000-8000-00805f9b34fb"

        const val PREFS_BT = "universal_glasses_rokid_bt_reconnect"
        const val PREF_KEY_SOCKET_UUID = "socket_uuid"
        const val PREF_KEY_MAC_ADDRESS = "mac_address"
    }
}


