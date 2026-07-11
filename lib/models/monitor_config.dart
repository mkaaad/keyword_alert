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
}
