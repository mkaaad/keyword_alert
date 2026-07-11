import 'dart:async';
import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

class AlarmService {
  /// New channel id so existing installs pick up USAGE_ALARM attributes.
  static const _channelId = 'keyword_alert_alarm_v2';
  static const _channelName = '关键词闹铃提醒';
  static const _channelDesc = '检测到关键词时播放系统默认闹铃';
  static const _native = MethodChannel('com.keyword/alarm');

  final AudioPlayer _player = AudioPlayer();
  final FlutterLocalNotificationsPlugin _notifications =
      FlutterLocalNotificationsPlugin();
  Timer? _alarmTimer;
  bool _isRinging = false;
  bool _initialized = false;

  Future<void> initialize() async {
    if (_initialized) return;
    try {
      const androidSettings =
          AndroidInitializationSettings('@mipmap/ic_launcher');
      const iosSettings = DarwinInitializationSettings();
      await _notifications.initialize(
        const InitializationSettings(android: androidSettings, iOS: iosSettings),
      );

      final androidPlugin = _notifications.resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin>();
      await androidPlugin?.createNotificationChannel(
        const AndroidNotificationChannel(
          _channelId,
          _channelName,
          description: _channelDesc,
          importance: Importance.max,
          playSound: true,
          enableVibration: true,
          audioAttributesUsage: AudioAttributesUsage.alarm,
        ),
      );
      _initialized = true;
    } catch (e) {
      debugPrint('AlarmService.initialize failed: $e');
    }
  }

  bool get isRinging => _isRinging;

  Future<void> trigger({required String keyword, required int count}) async {
    if (_isRinging) return;
    _isRinging = true;
    _usingNativeRingtone = false;

    // Prefer device default alarm ringtone (TYPE_ALARM / 闹钟音量).
    try {
      final ok = await _native.invokeMethod<bool>('playSystemAlarm');
      if (ok == true) {
        _usingNativeRingtone = true;
        debugPrint('AlarmService: system alarm ringtone started');
      } else {
        await _playAssetFallback();
      }
    } catch (e) {
      debugPrint('AlarmService native ringtone failed: $e');
      await _playAssetFallback();
    }

    try {
      await _notifications.show(
        0,
        '⚠️ 关键词提醒',
        '"$keyword" 在时间窗口内出现 $count 次，注意是否在点名！',
        const NotificationDetails(
          android: AndroidNotificationDetails(
            _channelId,
            _channelName,
            channelDescription: _channelDesc,
            importance: Importance.max,
            priority: Priority.max,
            playSound: true,
            enableVibration: true,
            category: AndroidNotificationCategory.alarm,
            audioAttributesUsage: AudioAttributesUsage.alarm,
          ),
        ),
      );
    } catch (e) {
      debugPrint('Alarm notification failed: $e');
    }

    _alarmTimer?.cancel();
    _alarmTimer = Timer(const Duration(seconds: 30), stop);
  }

  Future<void> _playAssetFallback() async {
    try {
      await _player.setAudioContext(
        AudioContext(
          android: const AudioContextAndroid(
            isSpeakerphoneOn: true,
            stayAwake: true,
            contentType: AndroidContentType.sonification,
            usageType: AndroidUsageType.alarm,
            audioFocus: AndroidAudioFocus.gainTransientMayDuck,
          ),
        ),
      );
      await _player.setReleaseMode(ReleaseMode.loop);
      await _player.play(AssetSource('sounds/alarm.wav'));
      debugPrint('AlarmService: asset alarm.wav fallback started');
    } catch (e) {
      debugPrint('Alarm audio play failed: $e');
    }
  }

  Future<void> stop() async {
    if (!_isRinging) return;
    _isRinging = false;
    _alarmTimer?.cancel();
    _alarmTimer = null;
    try {
      await _native.invokeMethod<bool>('stopSystemAlarm');
    } catch (e) {
      debugPrint('AlarmService stopSystemAlarm failed: $e');
    }
    try {
      await _player.stop();
    } catch (e) {
      debugPrint('Alarm stop failed: $e');
    }
    _usingNativeRingtone = false;
  }

  void dispose() {
    _alarmTimer?.cancel();
    unawaited(stop());
    _player.dispose();
  }
}
