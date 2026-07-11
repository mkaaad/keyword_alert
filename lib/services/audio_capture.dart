import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class AudioCaptureService {
  static const _channel = MethodChannel('com.keyword/audio_capture');
  static const _eventChannel = EventChannel('com.keyword/asr_stream');

  Stream<String>? _asrStream;

  Future<bool> start() async {
    try {
      final result = await _channel.invokeMethod<bool>('startCapture');
      return result ?? false;
    } on PlatformException catch (e) {
      debugPrint('AudioCapture start failed: ${e.message}');
      return false;
    }
  }

  Future<void> stop() async {
    try {
      await _channel.invokeMethod('stopCapture');
    } on PlatformException catch (e) {
      debugPrint('AudioCapture stop failed: ${e.message}');
    }
  }

  Stream<String> get asrStream {
    _asrStream ??= _eventChannel
        .receiveBroadcastStream()
        .where((event) => event is String && event.isNotEmpty)
        .cast<String>();
    return _asrStream!;
  }

  Future<bool> isActive() async {
    try {
      final result = await _channel.invokeMethod<bool>('isActive');
      return result ?? false;
    } on PlatformException {
      return false;
    }
  }
}
