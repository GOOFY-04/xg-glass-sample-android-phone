package com.universalglasses.device.frame.flutter

import com.universalglasses.core.CaptureOptions
import com.universalglasses.core.CapturedImage
import com.universalglasses.core.DisplayOptions
import com.universalglasses.core.GlassesEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Bridge interface implemented by the HOST APP, backed by its embedded Flutter module.
 *
 * Why this exists:
 * - This SDK wants to support Frame via Flutter without forcing a Flutter dependency on all consumers.
 * - So this module defines the contract; the app wires it to MethodChannel/EventChannel.
 */
interface FrameFlutterBridge {
    /** Bridge-side connection state (mirrors the Flutter module state). */
    val state: StateFlow<FrameFlutterState>

    /** Bridge-side events from Flutter (logs/tap/etc.). */
    val events: Flow<GlassesEvent>

    suspend fun connect(): Result<Unit>
    suspend fun disconnect()

    suspend fun capturePhoto(options: CaptureOptions): Result<CapturedImage>
    suspend fun displayText(text: String, options: DisplayOptions): Result<Unit>
}

sealed class FrameFlutterState {
    data object Disconnected : FrameFlutterState()
    data object Connecting : FrameFlutterState()
    data object Connected : FrameFlutterState()
    data class Error(val message: String) : FrameFlutterState()
}


