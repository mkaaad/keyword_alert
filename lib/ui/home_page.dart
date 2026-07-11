import 'package:flutter/material.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/monitor_config.dart';
import '../services/keyword_monitor.dart';
import '../services/alarm_service.dart';
import '../services/audio_capture.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  final AudioCaptureService _audioCapture = AudioCaptureService();
  final AlarmService _alarm = AlarmService();

  MonitorConfig _config = MonitorConfig.defaultConfig;
  KeywordMonitor? _monitor;
  bool _isMonitoring = false;
  bool _isStarting = false;
  String _lastText = '等待识别...';
  int _hitCount = 0;
  final List<String> _recentTexts = [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadConfig();
    _alarm.initialize();
    FlutterForegroundTask.initCommunicationPort();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _audioCapture.stop();
    super.dispose();
  }

  Future<void> _loadConfig() async {
    final prefs = await SharedPreferences.getInstance();
    final keyword = prefs.getString('keyword') ?? '老师';
    final threshold = prefs.getInt('threshold') ?? 3;
    final windowSeconds = prefs.getInt('windowSeconds') ?? 60;
    setState(() {
      _config = MonitorConfig(
        keyword: keyword,
        threshold: threshold,
        window: Duration(seconds: windowSeconds),
      );
      _createMonitor();
    });
  }

  void _createMonitor() {
    _monitor = KeywordMonitor(
      config: _config,
      onHit: (hit) {
        setState(() {
          _hitCount = _monitor!.recentHits.length;
          _recentTexts.insert(0, '${hit.timestamp.toString().substring(11, 19)} ${hit.context}');
          if (_recentTexts.length > 20) _recentTexts.removeLast();
        });
      },
      onTrigger: (config, count) {
        _alarm.trigger(keyword: config.keyword, count: count);
      },
    );
  }

  Future<void> _toggleMonitoring() async {
    if (_isMonitoring) {
      await _audioCapture.stop();
      await FlutterForegroundTask.stopService();
      setState(() => _isMonitoring = false);
    } else {
      setState(() => _isStarting = true);
      final ok = await _audioCapture.start();
      if (!ok) {
        if (mounted) {
          setState(() => _isStarting = false);
          showDialog(
            context: context,
            builder: (ctx) => AlertDialog(
              title: const Text('启动失败'),
              content: const Text('音频捕获启动失败，请检查 Magisk 模块是否安装并授予 Root 权限。\n\n'
                  '可能原因：\n1. 未授予 Root 权限\n'
                  '2. REMOTE_SUBMIX 未注入\n'
                  '3. 设备不支持系统音频捕获'),
              actions: [
                FilledButton(onPressed: () => Navigator.pop(ctx), child: const Text('知道了')),
              ],
            ),
          );
        }
        return;
      }
      _monitor?.reset();
      _createMonitor();
      _audioCapture.asrStream.listen((text) {
        setState(() {
          _lastText = text;
          _recentTexts.insert(0, text);
          if (_recentTexts.length > 50) _recentTexts.removeLast();
        });
        _monitor?.feed(text);
      });
      await FlutterForegroundTask.startService(
        notificationTitle: '关键词监控运行中',
        notificationText: '正在监听"${_config.keyword}"',
        callback: _backgroundTaskCallback,
      );
      setState(() {
        _isMonitoring = true;
        _isStarting = false;
        _hitCount = 0;
        _recentTexts.clear();
      });
    }
  }

  @pragma('vm:entry-point')
  static Future<void> _backgroundTaskCallback() async {
    FlutterForegroundTask.setTaskHandler(BackgroundTaskHandler());
  }

  Future<void> _showConfigDialog() async {
    final keywordCtrl = TextEditingController(text: _config.keyword);
    final thresholdCtrl = TextEditingController(text: _config.threshold.toString());
    final windowCtrl = TextEditingController(text: _config.window.inSeconds.toString());
    final result = await showDialog<Map<String, String>>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('监控配置'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(controller: keywordCtrl, decoration: const InputDecoration(labelText: '关键词')),
            TextField(controller: thresholdCtrl, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: '触发阈值（次）')),
            TextField(controller: windowCtrl, keyboardType: TextInputType.number, decoration: const InputDecoration(labelText: '时间窗口（秒）')),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(ctx, {'keyword': keywordCtrl.text, 'threshold': thresholdCtrl.text, 'window': windowCtrl.text}), child: const Text('保存')),
        ],
      ),
    );
    if (result != null) {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('keyword', result['keyword']!);
      await prefs.setInt('threshold', int.parse(result['threshold']!));
      await prefs.setInt('windowSeconds', int.parse(result['window']!));
      await _loadConfig();
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        appBar: AppBar(
          title: const Text('关键词监控'),
          actions: [
            IconButton(
              icon: const Icon(Icons.settings),
              onPressed: _isMonitoring ? null : _showConfigDialog,
            ),
          ],
        ),
        body: Padding(
          padding: const EdgeInsets.all(16.0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16.0),
                  child: Column(
                    children: [
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(_isMonitoring ? '🟢 监控中' : '⚪ 已停止', style: Theme.of(context).textTheme.titleMedium),
                          Text('关键词: "${_config.keyword}"', style: Theme.of(context).textTheme.bodyMedium),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceAround,
                        children: [
                          _StatBox(label: '阈值', value: '${_config.threshold}次'),
                          _StatBox(label: '窗口', value: '${_config.window.inSeconds}秒'),
                          _StatBox(label: '命中', value: '$_hitCount'),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(12.0),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('最新识别:', style: Theme.of(context).textTheme.labelMedium),
                      const SizedBox(height: 4),
                      Text(_lastText, style: Theme.of(context).textTheme.bodyLarge),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
              Expanded(
                child: Card(
                  child: ListView.builder(
                    itemCount: _recentTexts.length,
                    itemBuilder: (_, i) => Padding(
                      padding: const EdgeInsets.symmetric(vertical: 2.0),
                      child: Text(_recentTexts[i], style: Theme.of(context).textTheme.bodySmall),
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 16),
              SizedBox(
                height: 56,
                child: FilledButton.icon(
                  onPressed: _isStarting ? null : _toggleMonitoring,
                  icon: _isStarting
                      ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                      : Icon(_isMonitoring ? Icons.stop : Icons.play_arrow),
                  label: Text(_isStarting ? '启动中...' : (_isMonitoring ? '停止监控' : '开始监控')),
                  style: FilledButton.styleFrom(
                    backgroundColor: _isMonitoring ? Colors.red : Colors.green,
                  ),
                ),
              ),
              if (_alarm.isRinging)
                Padding(
                  padding: const EdgeInsets.only(top: 8.0),
                  child: SizedBox(
                    height: 40,
                    child: OutlinedButton.icon(
                      onPressed: () { _alarm.stop(); setState(() {}); },
                      icon: const Icon(Icons.alarm_off),
                      label: const Text('关闭提醒'),
                      style: OutlinedButton.styleFrom(foregroundColor: Colors.red),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _StatBox extends StatelessWidget {
  final String label;
  final String value;
  const _StatBox({required this.label, required this.value});
  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Text(label, style: Theme.of(context).textTheme.labelSmall),
        Text(value, style: Theme.of(context).textTheme.titleMedium),
      ],
    );
  }
}

class BackgroundTaskHandler extends TaskHandler {
  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {}
  @override
  void onRepeatEvent(DateTime timestamp) {}
  @override
  Future<void> onDestroy(DateTime timestamp) async {}
}
