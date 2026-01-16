import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/services.dart';
import 'package:frame_ble/brilliant_bluetooth.dart';
import 'package:frame_ble/brilliant_device.dart';
import 'package:frame_msg/rx/photo.dart';
import 'package:frame_msg/rx/tap.dart';
import 'package:frame_msg/tx/capture_settings.dart';
import 'package:frame_msg/tx/plain_text.dart';
import 'package:logging/logging.dart';

final _log = Logger("UniversalFrameBridge");

class UniversalFrameBridge {
  UniversalFrameBridge._();

  static final UniversalFrameBridge instance = UniversalFrameBridge._();

  static const _methodChannel = MethodChannel("universal_glasses/frame/methods");

  static const int _msgCaptureSettings = 0x20;
  static const int _msgPlainText = 0x21;

  StreamSubscription<String>? _stringSubs;
  StreamSubscription<int>? _tapSubs;

  BrilliantDevice? _device;
  String _state = "disconnected";

  bool _started = false;

  void start() {
    if (_started) return;
    _started = true;

    _methodChannel.setMethodCallHandler(_handleMethodCall);
    // Push initial state so Android can initialize its StateFlow.
    _emitState(_state);
  }

  Future<dynamic> _handleMethodCall(MethodCall call) async {
    switch (call.method) {
      case "connect":
        await _connect();
        return true;
      case "disconnect":
        await _disconnect();
        return true;
      case "capturePhoto":
        return await _capturePhoto(call.arguments as Map?);
      case "displayText":
        await _displayText(call.arguments as Map?);
        return true;
      default:
        throw PlatformException(code: "unimplemented", message: "Unknown method ${call.method}");
    }
  }

  Future<void> _connect() async {
    _setState("connecting");
    _emitLog("Frame: requesting BLE permission...");
    await BrilliantBluetooth.requestPermission();

    _emitLog("Frame: scanning...");
    final scanned = await BrilliantBluetooth.scan().first;

    _emitLog("Frame: connecting...");
    _device = await BrilliantBluetooth.connect(scanned);

    _emitLog("Frame: connected, sending break...");
    await _device!.sendBreakSignal();

    _wireLogsAndTap();

    _emitLog("Frame: uploading Lua libs + app...");
    await _uploadLuaAndStartApp();

    _setState("connected");
    _emitLog("Frame: ready");
  }

  Future<void> _disconnect() async {
    _setState("disconnected");
    await _stringSubs?.cancel();
    await _tapSubs?.cancel();
    _stringSubs = null;
    _tapSubs = null;

    try {
      await _device?.sendBreakSignal();
    } catch (_) {}
    try {
      await _device?.disconnect();
    } catch (_) {}
    _device = null;
  }

  void _wireLogsAndTap() {
    final dev = _device!;
    _stringSubs?.cancel();
    _tapSubs?.cancel();

    _stringSubs = dev.stringResponse.listen((s) {
      // `uploadScript()` uses print('\x02') as an ACK for each write step.
      // These show up as "blank" lines in the host UI, so filter them out.
      if (s.isEmpty || s == "\x02") return;
      final printable = s.replaceAll("\x02", "").trim();
      if (printable.isEmpty) return;

      // The SDK uses stdout for various messages; forward as logs.
      _emitLog("Frame: $printable");
    }, onError: (e) {
      _emitWarn("Frame stringResponse error: $e");
    });

    // Tap is sent by ug_frame_app.lua as a 0x09 single-byte raw data packet.
    final rxTap = RxTap();
    _tapSubs = rxTap.attach(dev.dataResponse).listen((count) {
      _emitTap(count);
    }, onError: (e) {
      _emitWarn("Frame tap stream error: $e");
    });
  }

  Future<Uint8List> _capturePhoto(Map? args) async {
    final dev = _requireDevice();

    final int timeoutMs = (args?["timeoutMs"] as int?) ?? 30000;
    final int quality = (args?["quality"] as int?) ?? 90;
    final int? targetW = args?["targetWidth"] as int?;
    final int? targetH = args?["targetHeight"] as int?;

    final int qualityIndex = _mapJpegQualityToIndex(quality);
    final String qualityStr = _qualityString(qualityIndex);
    final int resolution = _mapResolution(targetW, targetH);

    final tx = TxCaptureSettings(
      resolution: resolution,
      qualityIndex: qualityIndex,
      pan: 0,
      raw: false,
    );

    final rxPhoto = RxPhoto(
      quality: qualityStr,
      resolution: resolution,
      upright: true,
      isRaw: false,
    );

    // Send capture request (host->frame message)
    await dev.sendMessage(_msgCaptureSettings, tx.pack());

    // Receive exactly one jpeg
    return await rxPhoto.attach(dev.dataResponse).timeout(Duration(milliseconds: timeoutMs)).first;
  }

  Future<void> _displayText(Map? args) async {
    final dev = _requireDevice();
    final text = (args?["text"] as String?) ?? "";
    final bool force = (args?["force"] as bool?) ?? false;
    final String mode = (args?["mode"] as String?) ?? "replace";

    // We implement APPEND semantics in Dart so the frameside renderer can stay simple.
    if (mode == "append") {
      // naive append: ask the caller to send full text if they want better behavior.
      // (Host SDK already supports append at its level; keep here minimal.)
    }

    // Pack plain text message.
    final msg = TxPlainText(text: text, x: 1, y: 1, paletteOffset: 1, spacing: 4);
    await dev.sendMessage(_msgPlainText, msg.pack());

    if (force) {
      // no-op for Frame; the app loop will apply immediately.
    }
  }

  BrilliantDevice _requireDevice() {
    final dev = _device;
    if (dev == null) {
      throw PlatformException(code: "not_connected", message: "Frame is not connected");
    }
    return dev;
  }

  Future<void> _uploadLuaAndStartApp() async {
    final dev = _requireDevice();

    final dataMin = await rootBundle.loadString("assets/lua/data.min.lua");
    final cameraMin = await rootBundle.loadString("assets/lua/camera.min.lua");
    final plainTextMin = await rootBundle.loadString("assets/lua/plain_text.min.lua");
    final appLua = await rootBundle.loadString("assets/lua/ug_frame_app.lua");

    // Upload required libs. Use the exact filenames referenced by require() in ug_frame_app.lua
    await dev.uploadScript("data.min.lua", dataMin);
    await dev.uploadScript("camera.min.lua", cameraMin);
    await dev.uploadScript("plain_text.min.lua", plainTextMin);
    await dev.uploadScript("ug_frame_app.lua", appLua);

    // Start app loop (this will prevent further Lua REPL commands; use sendMessage afterwards).
    await dev.sendString("require('ug_frame_app');print('ug_frame_app started')", awaitResponse: false);
  }

  void _setState(String state) {
    _state = state;
    _emitState(state);
  }

  void _emitState(String value, {String? error}) {
    _emitEvent({
      "type": "state",
      "value": value,
      if (error != null) "error": error,
    });
  }

  void _emitLog(String message) {
    _emitEvent({"type": "log", "message": message});
  }

  void _emitWarn(String message) {
    _emitEvent({"type": "warning", "message": message});
  }

  void _emitTap(int count) {
    _emitEvent({"type": "tap", "count": count});
  }

  void _emitEvent(Map<String, dynamic> payload) {
    // Dart -> Android: bidirectional MethodChannel callback.
    // Android will implement a MethodCallHandler and route into FrameFlutterBridge.events/state.
    // Best-effort: ignore if Android side isn't ready yet.
    unawaited(_methodChannel.invokeMethod("onEvent", payload).catchError((_) {}));
  }

  static int _mapJpegQualityToIndex(int q) {
    if (q <= 30) return 0; // VERY_LOW
    if (q <= 50) return 1; // LOW
    if (q <= 70) return 2; // MEDIUM
    if (q <= 85) return 3; // HIGH
    return 4; // VERY_HIGH
  }

  static String _qualityString(int idx) {
    switch (idx) {
      case 0:
        return "VERY_LOW";
      case 1:
        return "LOW";
      case 2:
        return "MEDIUM";
      case 3:
        return "HIGH";
      default:
        return "VERY_HIGH";
    }
  }

  static int _mapResolution(int? w, int? h) {
    // Frame supports square even resolution 100..720.
    int base = 512;
    if (w != null && h != null) base = w < h ? w : h;
    else if (w != null) base = w;
    else if (h != null) base = h;
    if (base < 100) base = 100;
    if (base > 720) base = 720;
    if (base.isOdd) base -= 1;
    return base;
  }
}


