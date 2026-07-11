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

  /// Number of hits still inside the sliding time window.
  int get currentCount {
    _expireOldHits();
    return _hitTimestamps.length;
  }

  void _expireOldHits() {
    final cutoff = DateTime.now().subtract(_config.window);
    while (_hitTimestamps.isNotEmpty && _hitTimestamps.first.isBefore(cutoff)) {
      _hitTimestamps.removeFirst();
    }
  }

  /// Feed one ASR text segment. Returns true if the threshold was just crossed.
  bool feed(String text) {
    if (text.isEmpty || _config.keyword.isEmpty) return false;
    if (!text.contains(_config.keyword)) return false;

    _expireOldHits();
    final now = DateTime.now();

    // Count every non-overlapping occurrence in this segment.
    var from = 0;
    var added = 0;
    while (true) {
      final idx = text.indexOf(_config.keyword, from);
      if (idx < 0) break;
      _hitTimestamps.add(now);
      _hitLog.add(KeywordHit(
        keyword: _config.keyword,
        context: _extractContext(text, idx),
        timestamp: now,
      ));
      onHit?.call(_hitLog.last);
      added++;
      from = idx + _config.keyword.length;
    }
    if (added == 0) return false;

    final count = _hitTimestamps.length;
    if (count >= _config.threshold) {
      _hitTimestamps.clear();
      onTrigger?.call(_config, count);
      return true;
    }
    return false;
  }

  String _extractContext(String text, [int? index]) {
    final idx = index ?? text.indexOf(_config.keyword);
    if (idx < 0) return text;
    final start = (idx - 10).clamp(0, text.length);
    final end = (idx + _config.keyword.length + 10).clamp(0, text.length);
    return text.substring(start, end);
  }

  void reset() {
    _hitTimestamps.clear();
    _hitLog.clear();
  }
}
