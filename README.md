# Universal Glasses SDK (Android)

This folder contains a **new, standalone, multi-module Android SDK** that provides a single API surface for apps to support:

- **Rokid** (native Android SDK: Bluetooth + Wi‑Fi P2P media sync + custom view display)
- **Frame** (via a **Flutter module** adapter)
- **RayNeo** (split implementation: **phone-side installer** via ADB-over-TCP + **on-glasses runtime** client)

## Modules

- `universal`: single entry-point module that re-exports `core` + device implementations (one dependency line for apps)
- `core`: public interfaces + models (no vendor SDK dependencies)
- `device-rokid`: `core` implementation backed by Rokid CXR-M (`com.rokid.cxr:client-m`)
- `device-frame-flutter`: shared channel contract + glue types for Frame<->Flutter
- `device-frame-embedded`: Frame implementation (SDK-owned `FlutterEngine` + `MethodChannel`; app does *not* implement a bridge)
- `device-rayneo-installer`: **phone-side** RayNeo client; `connect()` installs/deploys the on-glasses APK via **ADB-over-TCP** (`adbd:5555`)
- `device-rayneo-runtime`: **on-glasses** RayNeo client; runs inside the glasses app process and implements `capturePhoto()` + `display()`
- `app` (host stub): internal-only Android application module required by Flutter’s Gradle plugin when we embed the generated module during development

## Build notes

- `minSdk` is **28** (required by Rokid SDK).
- For **in-repo development**, Frame support expects a generated Flutter module at `./frame_module` (see `frame_module/README.md`).
- For **real distribution**, the intent is to ship/publish a **prebuilt Flutter AAR** with the SDK so app developers don’t need Flutter or `frame_module/` sources.

## How an app uses the SDK (high level)

1. Pick device implementation:
   - Rokid: create `RokidGlassesClient(activity)`
   - Frame: create `EmbeddedFrameGlassesClient(context)`
   - RayNeo (phone-side installer): create `RayNeoInstallerGlassesClient(context, RayNeoInstallerConfig(...))`
   - RayNeo (on-glasses runtime): create `RayNeoRuntimeGlassesClient(context)`
2. Call:
   - `connect()`
   - `capturePhoto()`
   - `display(text)`
3. Subscribe to:
   - `state` (`StateFlow<ConnectionState>`)
   - `events` (`Flow<GlassesEvent>`)

> Note: RayNeo support is intentionally split across **two processes**. The **installer** client only handles deploying the on-glasses APK (it does not support `capturePhoto()` / `display()` from the phone). The **runtime** client is the one that runs on the glasses and performs capture/display.
