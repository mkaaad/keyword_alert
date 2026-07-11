import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class AudioCaptureService {
  static const _channel = MethodChannel('com.keyword/audio_capture');
  static const _eventChannel = EventChannel('com.keyword/asr_stream');

  StreamSubscription<String>? _subscription;
  StreamController<String>? _controller;

  Future<bool> start() async {
    try {
      final result = await _channel.invokeMethod<bool>('startCapture');
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('AudioCapture start failed: ${e.message}');
      return false;
    } on MissingPluginException catch (e) {
      debugPrint('AudioCapture plugin missing: $e');
      return false;
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

  /// Single-subscription style broadcast of ASR text. Calling again replaces
  /// the previous listener (prevents leak on restart).
  StreamSubscription<String> listen(
    void Function(String text) onData, {
    void Function(Object error)? onError,
  }) {
    _subscription?.cancel();
    _controller?.close();
    final controller = StreamController<String>.broadcast();
    _controller = controller;

    _subscription = _eventChannel
        .receiveBroadcastStream()
        .where((event) => event is String && event.isNotEmpty)
        .cast<String>()
        .listen(
      (text) {
        if (!controller.isClosed) controller.add(text);
        onData(text);
      },
      onError: (Object e, StackTrace st) {
        debugPrint('ASR stream error: $e');
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
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  Future<void> dispose() async {
    await stop();
    await _controller?.close();
    _controller = null;
  }
}
