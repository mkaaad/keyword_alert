import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class CaptureStartResult {
  final bool ok;
  final String mode; // playback | remote_submix | ocr | ocr+playback | ...
  final String? error;
  final bool ocr;
  final bool audio;

  const CaptureStartResult({
    required this.ok,
    required this.mode,
    this.error,
    this.ocr = false,
    this.audio = false,
  });

  /// True when any usable capture path is active.
  bool get isReady {
    if (!ok || mode == 'none' || mode.isEmpty) return false;
    return ocr ||
        audio ||
        mode == 'playback' ||
        mode == 'remote_submix' ||
        mode == 'ocr' ||
        mode.startsWith('ocr');
  }

  @Deprecated('Use isReady')
  bool get isPlayback => isReady;

  bool get hasOcr =>
      ocr || mode == 'ocr' || mode.startsWith('ocr+') || mode.contains('ocr');

  bool get hasAudio =>
      audio ||
      mode.contains('playback') ||
      mode.contains('remote_submix');
}

class AudioCaptureService {
  static const _channel = MethodChannel('com.keyword/audio_capture');
  static const _asrChannel = EventChannel('com.keyword/asr_stream');
  static const _ocrChannel = EventChannel('com.keyword/ocr_stream');
  static const _logChannel = EventChannel('com.keyword/debug_log');

  StreamSubscription<String>? _asrSub;
  StreamSubscription<String>? _ocrSub;
  StreamSubscription<String>? _logSubscription;

  /// Independent paths: [audioEnabled] = system playback ASR, [ocrEnabled] = screen OCR.
  Future<CaptureStartResult> start({
    bool ocrEnabled = false,
    bool audioEnabled = true,
  }) async {
    try {
      final raw = await _channel.invokeMethod<dynamic>(
        'startCapture',
        <String, dynamic>{
          'ocrEnabled': ocrEnabled,
          'audioEnabled': audioEnabled,
        },
      );
      if (raw is Map) {
        final mode = (raw['mode'] as String?) ?? 'none';
        return CaptureStartResult(
          ok: raw['ok'] == true,
          mode: mode,
          error: raw['error'] as String?,
          ocr: raw['ocr'] == true ||
              mode == 'ocr' ||
              mode.startsWith('ocr'),
          audio: raw['audio'] == true ||
              mode.contains('playback') ||
              mode.contains('remote_submix'),
        );
      }
      final ok = raw == true;
      return CaptureStartResult(ok: ok, mode: ok ? 'unknown' : 'none');
    } on PlatformException catch (e) {
      debugPrint('Capture start failed: ${e.message}');
      return CaptureStartResult(ok: false, mode: 'none', error: e.message);
    } on MissingPluginException catch (e) {
      debugPrint('Capture plugin missing: $e');
      return const CaptureStartResult(
        ok: false,
        mode: 'none',
        error: 'missing_plugin',
      );
    }
  }

  Future<void> stop() async {
    await _asrSub?.cancel();
    await _ocrSub?.cancel();
    _asrSub = null;
    _ocrSub = null;
    try {
      await _channel.invokeMethod('stopCapture');
    } on PlatformException catch (e) {
      debugPrint('Capture stop failed: ${e.message}');
    } on MissingPluginException catch (e) {
      debugPrint('Capture plugin missing: $e');
    }
  }

  Future<String> captureMode() async {
    try {
      final m = await _channel.invokeMethod<String>('captureMode');
      return m ?? 'none';
    } catch (_) {
      return 'none';
    }
  }

  StreamSubscription<String> listenLogs(void Function(String line) onLine) {
    _logSubscription?.cancel();
    _logSubscription = _logChannel
        .receiveBroadcastStream()
        .where((e) => e is String && e.isNotEmpty)
        .cast<String>()
        .listen(onLine, onError: (Object e) {
      debugPrint('debug_log error: $e');
    });
    return _logSubscription!;
  }

  /// Audio ASR text only.
  StreamSubscription<String> listenAsr(
    void Function(String text) onData, {
    void Function(Object error)? onError,
  }) {
    _asrSub?.cancel();
    _asrSub = _asrChannel
        .receiveBroadcastStream()
        .where((event) => event is String && event.isNotEmpty)
        .cast<String>()
        .listen(
      onData,
      onError: (Object e, StackTrace st) {
        debugPrint('ASR stream error: $e');
        onError?.call(e);
      },
      cancelOnError: false,
    );
    return _asrSub!;
  }

  /// Screen OCR text only (no audio).
  StreamSubscription<String> listenOcr(
    void Function(String text) onData, {
    void Function(Object error)? onError,
  }) {
    _ocrSub?.cancel();
    _ocrSub = _ocrChannel
        .receiveBroadcastStream()
        .where((event) => event is String && event.isNotEmpty)
        .cast<String>()
        .listen(
      onData,
      onError: (Object e, StackTrace st) {
        debugPrint('OCR stream error: $e');
        onError?.call(e);
      },
      cancelOnError: false,
    );
    return _ocrSub!;
  }

  /// @deprecated Prefer [listenAsr] + [listenOcr].
  StreamSubscription<String> listen(
    void Function(String text) onData, {
    void Function(Object error)? onError,
  }) {
    return listenAsr(onData, onError: onError);
  }

  Future<bool> isActive() async {
    try {
      final result = await _channel.invokeMethod<bool>('isActive');
      return result ?? false;
    } catch (_) {
      return false;
    }
  }

  Future<void> dispose() async {
    await _logSubscription?.cancel();
    _logSubscription = null;
    await stop();
  }
}
