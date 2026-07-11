import 'dart:async';
import 'package:audioplayers/audioplayers.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

class AlarmService {
  final AudioPlayer _player = AudioPlayer();
  final FlutterLocalNotificationsPlugin _notifications =
      FlutterLocalNotificationsPlugin();
  Timer? _alarmTimer;
  bool _isRinging = false;

  Future<void> initialize() async {
    const androidSettings =
        AndroidInitializationSettings('@mipmap/ic_launcher');
    const iosSettings = DarwinInitializationSettings();
    await _notifications.initialize(
      const InitializationSettings(android: androidSettings, iOS: iosSettings),
    );
  }

  bool get isRinging => _isRinging;

  Future<void> trigger({required String keyword, required int count}) async {
    if (_isRinging) return;
    _isRinging = true;
    await _player.setReleaseMode(ReleaseMode.loop);
    await _player.play(AssetSource('sounds/alarm.wav'));
    await _notifications.show(
      0,
      '⚠️ 关键词提醒',
      '"$keyword" 频繁出现（已触发 $count 次），注意是否在点名！',
      const NotificationDetails(
        android: AndroidNotificationDetails(
          'keyword_alert_channel',
          '关键词监控',
          channelDescription: '检测到关键词频繁出现时发送提醒',
          importance: Importance.high,
          priority: Priority.high,
          playSound: true,
          enableVibration: true,
        ),
      ),
    );
    _alarmTimer?.cancel();
    _alarmTimer = Timer(const Duration(seconds: 30), stop);
  }

  void stop() {
    if (!_isRinging) return;
    _isRinging = false;
    _alarmTimer?.cancel();
    _player.stop();
  }
}
