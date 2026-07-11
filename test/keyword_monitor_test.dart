import 'package:flutter_test/flutter_test.dart';
import 'package:keyword_alert/models/monitor_config.dart';
import 'package:keyword_alert/services/keyword_monitor.dart';

void main() {
  group('KeywordMonitor', () {
    test('does not trigger below threshold', () {
      var triggered = false;
      final monitor = KeywordMonitor(
        config: const MonitorConfig(
          keyword: '老师',
          threshold: 3,
          window: Duration(seconds: 60),
        ),
        onTrigger: (_, __) => triggered = true,
      );

      expect(monitor.feed('老师好'), isFalse);
      expect(monitor.feed('你好老师'), isFalse);
      expect(triggered, isFalse);
      expect(monitor.currentCount, 2);
    });

    test('triggers when threshold is reached', () {
      var triggerCount = 0;
      final monitor = KeywordMonitor(
        config: const MonitorConfig(
          keyword: '老师',
          threshold: 3,
          window: Duration(seconds: 60),
        ),
        onTrigger: (_, count) => triggerCount = count,
      );

      monitor.feed('老师');
      monitor.feed('老师');
      final fired = monitor.feed('老师');

      expect(fired, isTrue);
      expect(triggerCount, 3);
      // Window timestamps cleared after trigger.
      expect(monitor.currentCount, 0);
    });

    test('counts multiple occurrences in one segment', () {
      var triggerCount = 0;
      final monitor = KeywordMonitor(
        config: const MonitorConfig(
          keyword: '老师',
          threshold: 2,
          window: Duration(seconds: 60),
        ),
        onTrigger: (_, count) => triggerCount = count,
      );

      final fired = monitor.feed('老师老师');
      expect(fired, isTrue);
      expect(triggerCount, 2);
    });

    test('ignores text without keyword', () {
      final monitor = KeywordMonitor(
        config: const MonitorConfig(keyword: '老师', threshold: 1),
      );
      expect(monitor.feed('同学们好'), isFalse);
      expect(monitor.currentCount, 0);
    });

    test('reset clears state', () {
      final monitor = KeywordMonitor(
        config: const MonitorConfig(keyword: '老师', threshold: 5),
      );
      monitor.feed('老师');
      monitor.feed('老师');
      monitor.reset();
      expect(monitor.currentCount, 0);
      expect(monitor.recentHits, isEmpty);
    });
  });
}
