package com.universalglasses.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A minimal, unified API for AI glasses.
 *
 * Design goals:
 * - Keep app developers on a stable API surface
 * - Hide transport differences (Frame BLE messages vs Rokid Wi‑Fi P2P sync)
 * - Provide observability via state + events
 */
interface GlassesClient {
    val model: GlassesModel
    val capabilities: DeviceCapabilities

    /** Connection lifecycle state. */
    val state: StateFlow<ConnectionState>

    /** Non-fatal events (logs, warnings, tap events, etc.). */
    val events: Flow<GlassesEvent>

    /** Establish connection to the glasses (and any required side-channels like Wi‑Fi P2P). */
    suspend fun connect(): Result<Unit>

    /** Tear down connection and release resources. Safe to call multiple times. */
    suspend fun disconnect()

    /** Capture a photo and return JPEG bytes (plus metadata when available). */
    suspend fun capturePhoto(options: CaptureOptions = CaptureOptions()): Result<CapturedImage>

    /** Display text on the glasses. */
    suspend fun display(text: String, options: DisplayOptions = DisplayOptions()): Result<Unit>

    /**
     * Play audio on the glasses.
     *
     * - [AudioSource.Tts]: text → on-device TTS. Requires [DeviceCapabilities.canPlayTts].
     * - [AudioSource.RawBytes]: encoded/PCM bytes → direct playback. Requires [DeviceCapabilities.canPlayAudioBytes].
     *
     * Returns [GlassesError.Unsupported] when the device does not support the requested source type.
     */
    suspend fun playAudio(
        source: AudioSource,
        options: PlayAudioOptions = PlayAudioOptions(),
    ): Result<Unit>

    /**
     * Start microphone capture and return a session that streams audio chunks.
     *
     * Notes:
     * - This is an "audio stream" primitive. Apps can choose to save to WAV/AAC or stream to ASR.
     * - Implementations may require permissions (e.g. RECORD_AUDIO); the host app is responsible.
     */
    suspend fun startMicrophone(options: MicrophoneOptions = MicrophoneOptions()): Result<MicrophoneSession>

    // ── 第五原语：AI 推理 ────────────────────────────────────────────────

    /**
     * AI 文本/多模态推理（第五原语）。
     *
     * @param prompt      提示词
     * @param imageBase64 可选 JPEG base64 图片（多模态时传入）
     * @param options     推理参数（maxTokens, temperature, 云端 API 配置等）
     * @return 推理结果文本，或 [GlassesError.Unsupported] 如果设备不支持 AI
     */
    suspend fun aiGenerate(
        prompt: String,
        imageBase64: String? = null,
        options: AiGenerateOptions = AiGenerateOptions(),
    ): Result<String> = Result.failure(GlassesError.Unsupported("AI inference not supported on this device"))

    /**
     * 两阶段推理：视觉模型提取 → 文字模型解答。
     *
     * 默认实现合并为单阶段 [aiGenerate] 调用。
     * 支持多模型的设备可覆盖此方法做真正的两阶段流水线。
     *
     * @param extractPrompt      第一阶段提取提示词（发给视觉模型）
     * @param buildSolvePrompt   第二阶段：接收提取结果，返回解答提示词（发给文字模型）
     * @param imageBase64        JPEG base64 图片
     */
    suspend fun aiGenerateTwoStage(
        extractPrompt: String,
        buildSolvePrompt: (String) -> String,
        imageBase64: String,
    ): Result<String> {
        return aiGenerate("$extractPrompt\n\n请基于以上提取内容进行解答。", imageBase64)
    }
}
