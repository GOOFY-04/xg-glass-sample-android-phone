<h3 align="center">
📱 xg-glass-sample-android-phone
</h3>

<h4 align="center">
Turn your Android phone into a fully-functional AI smart glasses device
</h4>

---

## About

This is a fork of [xg-glass-sdk](https://github.com/hkust-spark/xg-glass-sdk) that demonstrates how to **use an Android phone as a smart glasses simulator with on-device AI inference**.

The core contribution is the **fifth primitive** — `aiGenerate()` — which brings multimodal AI reasoning (text + vision) directly into the `GlassesClient` interface, plus a complete **PhoneGlasses** device module that implements all five primitives using phone hardware + JNI-embedded llama.cpp.

### Five Primitives

| # | Primitive | PhoneGlasses Implementation |
|:--|:----------|:----------------------------|
| 📸 | `capturePhoto()` | CameraX (back camera) |
| 🎤 | `startMicrophone()` | AudioRecord (16kHz mono PCM) |
| 🖥️ | `display()` | TextView overlay in host Activity |
| 🔊 | `playAudio()` | TTS (TextToSpeech) + PCM playback |
| 🤖 | `aiGenerate()` | **JNI llama.cpp** + **cloud API** (OpenAI/Anthropic) |

### The Fifth Primitive: AI Inference

`aiGenerate()` is a new addition to `GlassesClient` — it enables on-device and cloud-powered multimodal AI reasoning without any network dependency:

```kotlin
suspend fun aiGenerate(
    prompt: String,
    imageBase64: String? = null,
    options: AiGenerateOptions = AiGenerateOptions(),
): Result<String>
```

| Feature | Description |
|:---|:---|
| 🧠 **Multimodal** | Text + image input → text output (vision + reasoning) |
| 📱 **On-device** | JNI-embedded llama.cpp with GGUF models — no network, low latency |
| ☁️ **Cloud API** | OpenAI and Anthropic format support via `AiCloudConfig` |
| 🔄 **Two-stage** | `aiGenerateTwoStage()` — vision OCR → text reasoning pipeline |
| 🎛️ **Dual mode** | Settings panel: 💻 all-local / ☁️ all-cloud toggle with resizable UI sections |

Default implementation returns `GlassesError.Unsupported` — devices opt in by implementing it. **PhoneGlasses is the first device module to implement all five primitives.**

### Supported Devices

| Category | Products |
|:---|:---|
| 📱 **PhoneGlasses** | Android phone simulator with on-device AI (JNI llama.cpp) — **this fork's focus** |
| Rokid | Rokid Glasses |
| Meta | Meta Wearables |
| Brilliant Labs | Frame |
| RayNeo | x2 Glasses, x3 Pro Glasses |
| Omi | Omi Glass |

### Models

GGUF models are loaded from `/sdcard/PhoneGlassesModels/`:

| Model | Type | Use |
|:---|:---|:---|
| `moondream.gguf` + `moondream-mmproj.gguf` | Multimodal (791MB + 868MB) | Vision + text — fits phone RAM |
| `qwen2.5-1.5b.gguf` | Text-only (941MB) | Text inference |
| `llava-7b-q4_k_m.gguf` + `llava-7b-mmproj.gguf` | Multimodal (3.9GB + 596MB) | Vision (⚠️ heavy for phone) |

## Project Structure

```
devices/device-phoneglasses/       # PhoneGlasses device module (this fork's main addition)
  PhoneGlassesClient.kt           # Five-primitive implementation
core/
  GlassesClient.kt                # Interface with aiGenerate() + aiGenerateTwoStage()
  CaptureAndDisplay.kt            # AiGenerateOptions, AiCloudConfig, models
devices/device-*/                  # Other device modules (from upstream)
```

## Original Work

This fork adds:

- ✅ **Fifth primitive `aiGenerate()`** — multimodal AI inference contract in `GlassesClient`
- ✅ **`PhoneGlassesClient`** — complete Android phone implementation (CameraX + TTS + JNI + cloud)
- ✅ **JNI llama.cpp integration** — pure C JNI with mtmd multimodal vision pipeline
- ✅ **Cloud API support** — OpenAI/Anthropic format, auto-fallback, multimodal multi-format
- ✅ **Dual-mode settings panel** — local/cloud toggle with resizable UI sections
- ✅ **Two-stage inference** (`aiGenerateTwoStage`) — vision OCR → reasoning pipeline
- ✅ **Sub-batch splitting** — fixes llama_decode crash on mobile (n_ubatch=256 limit)
- ✅ **File-based crash logging** — SIGSEGV debugging for JNI on Android
- ✅ **Gallery save** — every `capturePhoto()` auto-saves to `DCIM/PhoneGlasses/`

### Key Design Decisions

- **Cloud API is NOT a separate backend** — it's an optional `cloudConfig` in `AiGenerateOptions`. Commands never need to know which backend is active.
- **JNI is pure C** (aarch64-linux-android-clang, not clang++) — zero C++ dependency
- **Memory safety** — native byte arrays are copied before `JNI_ABORT` release to prevent dangling pointer crashes
- **CPU tuning** — `n_ctx=1536`, `n_batch=256`, `n_threads=1` optimized for Snapdragon 888 mobile SoC
- **Resizable UI** — all four functional sections have SeekBar height control persisted to SharedPreferences

## Upstream

This project is based on [hkust-spark/xg-glass-sdk](https://github.com/hkust-spark/xg-glass-sdk). See the upstream [documentation](https://xg.glass/developer-guide/) for the original SDK and CLI tools.
