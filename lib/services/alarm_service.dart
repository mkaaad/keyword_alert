import 'dart:async';
import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

class AlarmService {
  static const _channelId = 'keyword_alert_channel';
  static const _channelName = '关键词监控';
  static const _channelDesc = '检测到关键词频繁出现时发送提醒';

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

      // Android 8+ requires an explicit notification channel.
      final androidPlugin = _notifications.resolvePlatformSpecificImplementation<
          AndroidFlutterLocalNotificationsPlugin>();
      await androidPlugin?.createNotificationChannel(
        const AndroidNotificationChannel(
          _channelId,
          _channelName,
          description: _channelDesc,
          importance: Importance.high,
          playSound: true,
          enableVibration: true,
        ),
      );
      _initialized = true;
    } catch (e) {
      // Platform plugins may be unavailable in unit/widget tests.
      debugPrint('AlarmService.initialize failed: $e');
    }
  }

  bool get isRinging => _isRinging;

  Future<void> trigger({required String keyword, required int count}) async {
    if (_isRinging) return;
    _isRinging = true;

    try {
      await _player.setReleaseMode(ReleaseMode.loop);
      await _player.play(AssetSource('sounds/alarm.wav'));
    } catch (e) {
      debugPrint('Alarm audio play failed: $e');
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
            importance: Importance.high,
            priority: Priority.high,
            playSound: true,
            enableVibration: true,
            category: AndroidNotificationCategory.alarm,
          ),
        ),
      );
    } catch (e) {
      debugPrint('Alarm notification failed: $e');
    }

    _alarmTimer?.cancel();
    _alarmTimer = Timer(const Duration(seconds: 30), stop);
  }

  Future<void> stop() async {
    if (!_isRinging) return;
    _isRinging = false;
    _alarmTimer?.cancel();
    _alarmTimer = null;
    try {
      await _player.stop();
    } catch (e) {
      debugPrint('Alarm stop failed: $e');
    }
  }

  void dispose() {
    _alarmTimer?.cancel();
    _player.dispose();
  }
}
