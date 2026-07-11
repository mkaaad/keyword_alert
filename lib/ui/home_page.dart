import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/monitor_config.dart';
import '../services/alarm_service.dart';
import '../services/audio_capture.dart';
import '../services/background_task.dart';
import '../services/keyword_monitor.dart';

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final AudioCaptureService _audioCapture = AudioCaptureService();
  final AlarmService _alarm = AlarmService();

  MonitorConfig _config = MonitorConfig.defaultConfig();
  KeywordMonitor? _monitor;
  bool _isMonitoring = false;
  bool _isStarting = false;
  String _lastText = '等待识别...';
  int _hitCount = 0;
  final List<String> _recentTexts = [];

  @override
  void initState() {
    super.initState();
    _loadConfig();
    _alarm.initialize();
    _initForegroundTask();
  }

  @override
  void dispose() {
    unawaited(_audioCapture.dispose());
    unawaited(_alarm.stop());
    _alarm.dispose();
    super.dispose();
  }

  void _initForegroundTask() {
    try {
      FlutterForegroundTask.init(
        androidNotificationOptions: AndroidNotificationOptions(
          channelId: 'keyword_alert_fg',
          channelName: '关键词监控服务',
          channelDescription: '后台监控运行时显示的通知',
          onlyAlertOnce: true,
        ),
        iosNotificationOptions: const IOSNotificationOptions(
          showNotification: false,
          playSound: false,
        ),
        foregroundTaskOptions: ForegroundTaskOptions(
          eventAction: ForegroundTaskEventAction.repeat(15000),
          autoRunOnBoot: false,
          autoRunOnMyPackageReplaced: false,
          allowWakeLock: true,
          allowWifiLock: false,
        ),
      );
    } catch (e) {
      debugPrint('FlutterForegroundTask.init failed: $e');
    }
  }

  Future<void> _loadConfig() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final keyword = prefs.getString('keyword') ?? '老师';
      final threshold = prefs.getInt('threshold') ?? 3;
      final windowSeconds = prefs.getInt('windowSeconds') ?? 60;
      if (!mounted) return;
      setState(() {
        _config = MonitorConfig(
          keyword: keyword,
          threshold: threshold.clamp(1, 100),
          window: Duration(seconds: windowSeconds.clamp(5, 3600)),
        );
        _createMonitor();
      });
    } catch (e) {
      debugPrint('Load config failed: $e');
      if (!mounted) return;
      setState(_createMonitor);
    }
  }

  void _createMonitor() {
    _monitor = KeywordMonitor(
      config: _config,
      onHit: (hit) {
        if (!mounted) return;
        setState(() {
          _hitCount = _monitor?.currentCount ?? 0;
        });
      },
      onTrigger: (config, count) async {
        await _alarm.trigger(keyword: config.keyword, count: count);
        if (mounted) setState(() {});
      },
    );
  }

  Future<void> _toggleMonitoring() async {
    if (_isMonitoring) {
      await _audioCapture.stop();
      try {
        await FlutterForegroundTask.stopService();
      } catch (e) {
        debugPrint('stopService failed: $e');
      }
      if (!mounted) return;
      setState(() {
        _isMonitoring = false;
        _lastText = '已停止';
      });
      return;
    }

    setState(() => _isStarting = true);

    // Android 14+: keep-alive FGS should be up before / while capturing.
    try {
      await FlutterForegroundTask.startService(
        notificationTitle: '关键词监控运行中',
        notificationText: '正在监听「${_config.keyword}」',
        callback: backgroundTaskCallback,
      );
    } catch (e) {
      debugPrint('startService failed: $e');
    }

    final ok = await _audioCapture.start();
    if (!ok) {
      try {
        await FlutterForegroundTask.stopService();
      } catch (_) {}
      if (mounted) {
        setState(() => _isStarting = false);
        await showDialog<void>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Text('启动失败'),
            content: const Text(
              '音频捕获启动失败。\n\n'
              '请允许「屏幕录制/投射」权限（用于捕获 B站/会议等系统播放声音）。\n\n'
              '其它可能原因：\n'
              '1. 未授予录音/通知权限\n'
              '2. 未允许前台服务通知\n'
              '3. ASR 模型未正确打包',
            ),
            actions: [
              FilledButton(
                onPressed: () => Navigator.pop(ctx),
                child: const Text('知道了'),
              ),
            ],
          ),
        );
      }
      return;
    }

    _monitor?.reset();
    _createMonitor();
    _audioCapture.listen(
      (text) {
        if (!mounted) return;
        setState(() {
          _lastText = text;
          _recentTexts.insert(0, text);
          if (_recentTexts.length > 50) _recentTexts.removeLast();
        });
        _monitor?.feed(text);
      },
      onError: (e) {
        debugPrint('ASR error: $e');
        if (mounted) {
          setState(() => _lastText = '识别出错: $e');
        }
      },
    );

    if (!mounted) return;
    setState(() {
      _isMonitoring = true;
      _isStarting = false;
      _hitCount = 0;
      _recentTexts.clear();
      _lastText = '等待识别...';
    });
  }

  Future<void> _showConfigDialog() async {
    final keywordCtrl = TextEditingController(text: _config.keyword);
    final thresholdCtrl =
        TextEditingController(text: _config.threshold.toString());
    final windowCtrl =
        TextEditingController(text: _config.window.inSeconds.toString());

    final result = await showDialog<Map<String, String>>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('监控配置'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: keywordCtrl,
              decoration: const InputDecoration(labelText: '关键词'),
            ),
            TextField(
              controller: thresholdCtrl,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: '触发阈值（次）'),
            ),
            TextField(
              controller: windowCtrl,
              keyboardType: TextInputType.number,
              decoration: const InputDecoration(labelText: '时间窗口（秒）'),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, {
              'keyword': keywordCtrl.text.trim(),
              'threshold': thresholdCtrl.text.trim(),
              'window': windowCtrl.text.trim(),
            }),
            child: const Text('保存'),
          ),
        ],
      ),
    );

    keywordCtrl.dispose();
    thresholdCtrl.dispose();
    windowCtrl.dispose();

    if (result == null || !mounted) return;

    final keyword = result['keyword'] ?? '';
    if (keyword.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('关键词不能为空')),
      );
      return;
    }

    final threshold = int.tryParse(result['threshold'] ?? '');
    final window = int.tryParse(result['window'] ?? '');
    if (threshold == null || threshold < 1 || window == null || window < 5) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('阈值需 ≥1，时间窗口需 ≥5 秒')),
      );
      return;
    }

    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('keyword', keyword);
      await prefs.setInt('threshold', threshold.clamp(1, 100));
      await prefs.setInt('windowSeconds', window.clamp(5, 3600));
      await _loadConfig();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('保存失败: $e')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
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
                        Text(
                          _isMonitoring ? '🟢 监控中' : '⚪ 已停止',
                          style: theme.textTheme.titleMedium,
                        ),
                        Text(
                          '关键词: "${_config.keyword}"',
                          style: theme.textTheme.bodyMedium,
                        ),
                      ],
                    ),
                    const SizedBox(height: 8),
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceAround,
                      children: [
                        _StatBox(label: '阈值', value: '${_config.threshold}次'),
                        _StatBox(
                          label: '窗口',
                          value: '${_config.window.inSeconds}秒',
                        ),
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
                    Text('最新识别:', style: theme.textTheme.labelMedium),
                    const SizedBox(height: 4),
                    Text(_lastText, style: theme.textTheme.bodyLarge),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            Expanded(
              child: Card(
                child: _recentTexts.isEmpty
                    ? Center(
                        child: Text(
                          '识别记录将显示在这里',
                          style: theme.textTheme.bodySmall,
                        ),
                      )
                    : ListView.builder(
                        padding: const EdgeInsets.all(8),
                        itemCount: _recentTexts.length,
                        itemBuilder: (_, i) => Padding(
                          padding: const EdgeInsets.symmetric(vertical: 2.0),
                          child: Text(
                            _recentTexts[i],
                            style: theme.textTheme.bodySmall,
                          ),
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
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(
                          strokeWidth: 2,
                          color: Colors.white,
                        ),
                      )
                    : Icon(_isMonitoring ? Icons.stop : Icons.play_arrow),
                label: Text(
                  _isStarting
                      ? '启动中...'
                      : (_isMonitoring ? '停止监控' : '开始监控'),
                ),
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
                    onPressed: () async {
                      await _alarm.stop();
                      if (mounted) setState(() {});
                    },
                    icon: const Icon(Icons.alarm_off),
                    label: const Text('关闭提醒'),
                    style: OutlinedButton.styleFrom(foregroundColor: Colors.red),
                  ),
                ),
              ),
          ],
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
