<h3 align="center">
xg.glass
</h3>

<h3 align="center">
Easy, fast, glasses application development for everyone
</h3>


<p align="center">
| <a href="https://xg.glass/developer-guide/"><b>Documentation</b></a> | <a href="https://xg.glass/blog/"><b>Blog</b></a> | <a href="https://github.com/hkust-spark/xg-glass-sample/"><b>Sample Applications</b></a>
</p>

🔥 We have built a xg.glass website to help you get started with xg.glass. Please visit [xg.glass](https://xg.glass) to learn more.

---

## About

xg.glass is a fast and easy-to-use library for smart glasses application development.

Smart glasses development is supposed to be easy. If you want to build an application, all you need is the following **five** interfaces:

- Video input from the camera (📸 capturePhoto)
- Audio input from the microphone (🎤 startMicrophone)
- Display output (🖥️ display)
- Audio output (🔊 playAudio)
- **AI inference** (🤖 aiGenerate) — NEW: on-device or cloud-powered multimodal reasoning

This is what xg.glass has extracted for you from tens of smart glasses SDKs. We hide all details of communicating with difference glasses' SDKs and make sure that the code that you develop based on xg.glass can smoothly run on multiple glasses or a simulator without any single line of additional effort.

Currently we support:

| Category | Products |
| --- | --- |
| Rokid | Rokid Glasses |
| Meta | Meta Wearables |
| Brilliant Labs | Frame |
| RayNeo | x2 Glasses, x3 Pro Glasses |
| Omi | Omi Glass |
| PhoneGlasses | 📱 Phone-based simulator with on-device AI (JNI llama.cpp) |
| *Simulation* | — |

We're working on and will support soon:

- **INMO**

Welcome the contributions from the community on more glasses!

## The Fifth Primitive: AI Inference

`GlassesClient` now includes a **fifth primitive** — `aiGenerate()` — for on-device and cloud-powered AI reasoning:

```kotlin
suspend fun aiGenerate(
    prompt: String,
    imageBase64: String? = null,
    options: AiGenerateOptions = AiGenerateOptions(),
): Result<String>
```

| Feature | Description |
|:---|:---|
| 🧠 **Multimodal** | Text + image input, text output |
| ☁️ **Cloud API** | OpenAI (gpt-4o) and Anthropic (claude-sonnet-4) via `AiCloudConfig` |
| 📱 **On-device** | JNI-embedded llama.cpp (PhoneGlasses) — no network needed |
| 🔄 **Two-stage** | `aiGenerateTwoStage()` – vision OCR → text reasoning pipeline |

Default implementation returns `GlassesError.Unsupported` — devices opt in by implementing it.

### PhoneGlasses

PhoneGlasses is a phone-based smart glasses simulator that turns your Android phone into a
fully-functional AI glasses device. It uses the phone's built-in camera, microphone, and
speaker to simulate all five primitives:

| Primitive | Phone Implementation |
|:---|:---|
| 📸 capturePhoto | CameraX (back camera) |
| 🖥️ display | TextView overlay in host Activity |
| 🔊 playAudio | TTS (TextToSpeech) + PCM/encoded audio |
| 🎤 startMicrophone | AudioRecord (16kHz mono PCM) |
| 🤖 aiGenerate | **JNI llama.cpp** (local GGUF models) + **cloud API** (OpenAI/Anthropic) |

Models (.gguf + mmproj) are loaded from `/sdcard/PhoneGlassesModels/`. Cloud API
credentials are configured via `AiCloudConfig` passed through `AiGenerateOptions`.

## Getting Started

### App developers (build apps with the SDK)

#### Host machine prerequisites

- **JDK 17 or 21**
- **Android SDK + Platform Tools** (ensure `adb` is on your `PATH`)
- **Flutter** (required because the SDK embeds a Flutter module at build time for Frame)
- **Android Emulator** (for simulation mode)

The **xg-glass CLI** (see below) can automatically set up the host prerequisites above. You'll also need an Android phone with USB debugging enabled for testing on real devices.

#### Installation

Currently, the SDK is consumed from source:

```bash
git clone <this-repo>
cd <this-repo>
pip install -e .
```

If a usage menu is printed by `xg-glass --help`, the xg-glass SDK is installed successfully.

#### *Meta AI Glasses setup*

*If you want to build with Meta AI glasses support, configure a GitHub Packages token for the Meta DAT SDK first.*

*Recommended local setup:*

```properties
# ~/.gradle/gradle.properties
github_token=ghp_xxxxxxxxxxxxx
```

*Shell-based setup:*

```bash
export GITHUB_TOKEN=ghp_xxxxxxxxxxxxx
```

*Notes:*

- *The token needs at least GitHub `read:packages` scope.*

#### Quickstart

The fastest workflow is to run a single Kotlin file:

```bash
xg-glass run /path/to/MyEntry.kt
```

`MyEntry.kt` must follow a small format contract. See the [Developer Guide](https://xg.glass/developer-guide/) for details. We provide several examples at :link:[xg-glass-sample](https://github.com/hkust-spark/xg-glass-sample)

For a stable workflow, generate a minimal project and iterate:

```bash
xg-glass init /path/to/myapp
cd /path/to/myapp
xg-glass build
xg-glass install
xg-glass run
```

#### Simulator

If you don't have glasses right now, we also support a simulator (simply add `--sim`) for development and testing. The WebCam on your PC will "act as" the camera on the glasses.

```bash
# Quick mode
xg-glass run --sim /path/to/MyEntry.kt

# Stable workflow
xg-glass init --sim /path/to/myapp
cd /path/to/myapp
xg-glass build
xg-glass install
xg-glass run
```

The launch of Android Emulator may take serval minutes. You can keep it on to save time for the next run.

#### Simulator with pre-recorded datasets

We support simulation with online or local video datasets.

```bash
xg-glass run --sim --video_url <video.url> /path/to/MyEntry.kt
```

or

```bash
xg-glass run --sim --local_video </path/to/local/video.mp4> /path/to/MyEntry.kt
```

We currently support video from YouTube and Bilibili.

For more details, see the following documentation:

- [Developer Guide](https://xg.glass/developer-guide/)
- [CLI Reference](https://xg.glass/cli-reference/)

#### AI-assisted development

We also provide [`VIBE_CODING.md`](./VIBE_CODING.md), a comprehensive reference specifically prepared for AI coding assistants such as ChatGPT, Claude, Cursor, and Copilot.

Developers can give this document directly to their AI assistant so it can reference the xg.glass SDK APIs, patterns, and examples when helping build applications.

### Contributors (extend the SDK)

If you want to **extend xg.glass itself** (new devices, new APIs, build tooling), start with [Contributor Guide](https://xg.glass/contributor-guide/).
