import 'dart:collection';
import '../models/monitor_config.dart';

class KeywordHit {
  final String keyword;
  final String context;
  final DateTime timestamp;
  const KeywordHit({
    required this.keyword,
    required this.context,
    required this.timestamp,
  });
}

class KeywordMonitor {
  final MonitorConfig _config;
  final Queue<DateTime> _hitTimestamps = Queue();
  final List<KeywordHit> _hitLog = [];
  final void Function(KeywordHit hit)? onHit;
  final void Function(MonitorConfig config, int count)? onTrigger;

  KeywordMonitor({
    required MonitorConfig config,
    this.onHit,
    this.onTrigger,
  }) : _config = config;

  MonitorConfig get config => _config;
  List<KeywordHit> get recentHits => List.unmodifiable(_hitLog);
  int get currentCount { _expireOldHits(); return _hitTimestamps.length; }

  void _expireOldHits() {
    final cutoff = DateTime.now().subtract(_config.window);
    while (_hitTimestamps.isNotEmpty && _hitTimestamps.first.isBefore(cutoff)) {
      _hitTimestamps.removeFirst();
    }
  }

  bool feed(String text) {
    if (!text.contains(_config.keyword)) return false;
    _expireOldHits();
    final now = DateTime.now();
    _hitTimestamps.add(now);
    _hitLog.add(KeywordHit(
      keyword: _config.keyword,
      context: _extractContext(text),
      timestamp: now,
    ));
    onHit?.call(_hitLog.last);
    if (_hitTimestamps.length >= _config.threshold) {
      _hitTimestamps.clear();
      onTrigger?.call(_config, _hitLog.length);
      return true;
    }
    return false;
  }

  String _extractContext(String text) {
    final idx = text.indexOf(_config.keyword);
    if (idx < 0) return text;
    final start = (idx - 10).clamp(0, text.length);
    final end = (idx + _config.keyword.length + 10).clamp(0, text.length);
    return text.substring(start, end);
  }

  void reset() { _hitTimestamps.clear(); _hitLog.clear(); }
}
