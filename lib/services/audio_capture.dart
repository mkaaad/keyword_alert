import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class CaptureStartResult {
  final bool ok;
  final String mode; // playback | remote_submix | ocr | ocr+playback | ...
  final String? error;

  const CaptureStartResult({
    required this.ok,
    required this.mode,
    this.error,
  });

  /// True when any usable capture path is active (audio and/or screen OCR).
  bool get isPlayback {
    if (mode == 'none' || mode.isEmpty) return false;
    return mode == 'playback' ||
        mode == 'playback_voice' ||
        mode == 'remote_submix' ||
        mode == 'ocr' ||
        mode.startsWith('ocr');
  }

  bool get hasOcr => mode == 'ocr' || mode.startsWith('ocr+') || mode.contains('ocr');
}

class AudioCaptureService {
  static const _channel = MethodChannel('com.keyword/audio_capture');
  static const _eventChannel = EventChannel('com.keyword/asr_stream');
  static const _logChannel = EventChannel('com.keyword/debug_log');

  StreamSubscription<String>? _subscription;
  StreamSubscription<String>? _logSubscription;

  /// [ocrEnabled] turns on full-screen MediaProjection OCR (ML Kit Chinese).
  Future<CaptureStartResult> start({bool ocrEnabled = false}) async {
    try {
      final raw = await _channel.invokeMethod<dynamic>(
        'startCapture',
        <String, dynamic>{'ocrEnabled': ocrEnabled},
      );
      if (raw is Map) {
        return CaptureStartResult(
          ok: raw['ok'] == true,
          mode: (raw['mode'] as String?) ?? 'none',
          error: raw['error'] as String?,
        );
      }
      final ok = raw == true;
      return CaptureStartResult(ok: ok, mode: ok ? 'unknown' : 'none');
    } on PlatformException catch (e) {
      debugPrint('AudioCapture start failed: ${e.message}');
      return CaptureStartResult(ok: false, mode: 'none', error: e.message);
    } on MissingPluginException catch (e) {
      debugPrint('AudioCapture plugin missing: $e');
      return const CaptureStartResult(ok: false, mode: 'none', error: 'missing_plugin');
    }
  }

  Future<void> stop() async {
    await _subscription?.cancel();
    _subscription = null;
    try {
      await _channel.invokeMethod('stopCapture');
    } on PlatformException catch (e) {
      debugPrint('AudioCapture stop failed: ${e.message}');
    } on MissingPluginException catch (e) {
      debugPrint('AudioCapture plugin missing: $e');
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

  /// Native + app diagnostic log lines (also in logcat as KeywordAlert).
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

  /// ASR and/or OCR text lines (OCR prefixed with `[OCR] `).
  StreamSubscription<String> listen(
    void Function(String text) onData, {
    void Function(Object error)? onError,
  }) {
    _subscription?.cancel();
    _subscription = _eventChannel
        .receiveBroadcastStream()
        .where((event) => event is String && event.isNotEmpty)
        .cast<String>()
        .listen(
      onData,
      onError: (Object e, StackTrace st) {
        debugPrint('ASR/OCR stream error: $e');
        onError?.call(e);
      },
      cancelOnError: false,
    );
    return _subscription!;
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
