package com.universalglasses.core

enum class GlassesModel {
    FRAME,
    META,
    ROKID,
    RAYNEO,
    SIMULATOR,
    OMI,
    /** Phone-based smart glasses simulator with on-device AI (JNI llama.cpp). */
    PHONEGLASSES,
}

data class DeviceCapabilities(
    val canCapturePhoto: Boolean = true,
    val canDisplayText: Boolean = true,
    val canRecordAudio: Boolean = false,
    /** Device can render text-to-speech via a built-in TTS engine (e.g. Rokid). */
    val canPlayTts: Boolean = false,
    /** Device can play raw/encoded audio bytes on the glasses speaker. */
    val canPlayAudioBytes: Boolean = false,
    val supportsTapEvents: Boolean = false,
    val supportsStreamingTextUpdates: Boolean = false,
)

data class CaptureOptions(
    /**
     * A unified "quality" knob.
     * - Rokid: mapped to JPEG quality 0..100 (default 90)
     * - Frame: mapped to SDK quality index or preset (implementation-defined)
     */
    val quality: Int? = null,
    /**
     * A unified "target size" knob.
     * - Rokid: mapped to width/height
     * - Frame: mapped to square resolution (implementation-defined)
     */
    val targetWidth: Int? = null,
    val targetHeight: Int? = null,
    val timeoutMs: Long = 30_000,
)

enum class DisplayMode { REPLACE, APPEND }

data class DisplayOptions(
    val mode: DisplayMode = DisplayMode.REPLACE,
    /** If true, bypass any throttling/dedup logic in the adapter. */
    val force: Boolean = false,
)

data class CapturedImage(
    val jpegBytes: ByteArray,
    val timestampMs: Long = System.currentTimeMillis(),
    val width: Int? = null,
    val height: Int? = null,
    val rotationDegrees: Int? = null,
    val sourceModel: GlassesModel,
)

// ── 第五原语：AI 推理相关模型 ─────────────────────────────────────────────

/**
 * AI 推理选项（第五原语 [GlassesClient.aiGenerate] 的参数）。
 *
 * @param maxTokens   最大生成 token 数
 * @param temperature 采样温度（0.0–2.0，越高越随机）
 * @param cloudConfig 云端 API 配置。为 null 时使用设备本地推理。
 */
data class AiGenerateOptions(
    val maxTokens: Int = 2048,
    val temperature: Float = 0.7f,
    val cloudConfig: AiCloudConfig? = null,
)

/**
 * 云端大模型 API 配置。
 *
 * 支持 OpenAI 和 Anthropic 两种 API 格式，
 * 均可处理多模态（图片 + 文本）输入。
 *
 * @param apiUrl   API 地址（如 https://api.openai.com/v1）
 * @param apiKey   API 密钥
 * @param model    模型名称（如 gpt-4o, claude-sonnet-4）
 * @param apiMode  "openai"（默认）或 "anthropic"
 */
data class AiCloudConfig(
    val apiUrl: String,
    val apiKey: String,
    val model: String,
    val apiMode: String = "openai",
) {
    companion object {
        /** 从设置 Map 构建（用于从 SharedPreferences 恢复）。 */
        fun fromSettings(settings: Map<String, String>): AiCloudConfig? {
            val url = settings["cloud_api_url"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val key = settings["cloud_api_key"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val model = settings["cloud_model"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val mode = settings["cloud_api_mode"]?.trim() ?: "openai"
            return AiCloudConfig(apiUrl = url, apiKey = key, model = model, apiMode = mode)
        }
    }
}
