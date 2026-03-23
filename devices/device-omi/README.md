## Omi device module

This module integrates **Omi Glass** into the unified `xg-glass` API surface.

- **Host platform**: Android (Kotlin).
- **Model**: `GlassesModel.OMI`.
- **Capabilities**:
  - Audio input (microphone streaming) over BLE – surfaced via `startMicrophone`.
  - Photo capture over BLE – surfaced via `capturePhoto`.
  - No display or audio playback primitives are exposed in the public BLE docs, so:
    - `display` returns `GlassesError.Unsupported`.
    - `playAudio` returns `GlassesError.Unsupported`.

### Usage

- Add `implementation(project(":device-omi"))` transitively via the `universal` module.
- In a host app, select `GlassesModel.OMI` to construct an `OmiGlassesClient` (see how other
  device clients are wired in the sample `MainActivity` template).

### Notes

- The current implementation focuses on **audio input** from Omi Glass using the documented
  BLE Audio Service and codec information from the Omi SDK report.
  Host-side apps are expected to decode Opus or PCM as needed and forward audio to their
  own transcription / processing pipeline.

