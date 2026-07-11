class MonitorConfig {
  final String keyword;
  final int threshold;
  final Duration window;

  const MonitorConfig({
    this.keyword = '老师',
    this.threshold = 3,
    this.window = const Duration(seconds: 60),
  });

  factory MonitorConfig.defaultConfig() => const MonitorConfig();

  MonitorConfig copyWith({
    String? keyword,
    int? threshold,
    Duration? window,
  }) {
    return MonitorConfig(
      keyword: keyword ?? this.keyword,
      threshold: threshold ?? this.threshold,
      window: window ?? this.window,
    );
  }

  @override
  String toString() =>
      'MonitorConfig(keyword: $keyword, threshold: $threshold, window: ${window.inSeconds}s)';
}
