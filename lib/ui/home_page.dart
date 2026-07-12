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
  String _captureMode = 'none'; // playback | none
  int _hitCount = 0;
  final List<String> _recentTexts = [];
  final List<String> _debugLogs = [];
  final ScrollController _logScroll = ScrollController();

  @override
  void initState() {
    super.initState();
    _loadConfig();
    _alarm.initialize();
    _initForegroundTask();
    _audioCapture.listenLogs(_onNativeLog);
    _appendLog('UI ready — 日志会显示在此，无需 adb');
  }

  void _onNativeLog(String line) {
    if (!mounted) return;
    setState(() {
      _debugLogs.add(line);
      if (_debugLogs.length > 300) {
        _debugLogs.removeRange(0, _debugLogs.length - 300);
      }
    });
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_logScroll.hasClients) {
        _logScroll.jumpTo(_logScroll.position.maxScrollExtent);
      }
    });
  }

  void _appendLog(String msg) {
    final now = DateTime.now();
    final t =
        '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}:${now.second.toString().padLeft(2, '0')}';
    _onNativeLog('$t UI $msg');
  }

  @override
  void dispose() {
    _logScroll.dispose();
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
    _appendLog('开始监控…（将请求屏幕录制/投射）');

    // Native: dialog first → mediaProjection FGS → AudioPlaybackCapture only (no mic).
    final start = await _audioCapture.start();
    _appendLog('start 结果 ok=${start.ok} mode=${start.mode} err=${start.error}');
    if (!start.ok || !start.isPlayback) {
      if (mounted) {
        setState(() {
          _isStarting = false;
          _captureMode = start.mode;
        });
        final err = start.error ?? 'unknown';
        await showDialog<void>(
          context: context,
          builder: (ctx) => AlertDialog(
            title: const Text('系统内录未成功'),
            content: Text(
              '未能捕获其它 App 的播放声音（不会静默改用麦克风）。\n\n'
              '错误码: $err\n\n'
              '请确认：\n'
              '1. 点了「立即开始」允许屏幕录制/投射\n'
              '2. 通知栏允许本应用前台服务\n'
              '3. 腾讯会议等走「通话/VoIP」音，请用 Magisk 模块安装（REMOTE_SUBMIX）\n'
              '4. 看下方「运行日志」：mode=remote_submix 或 playback，以及 RMS',
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

    try {
      await FlutterForegroundTask.startService(
        notificationTitle: '关键词监控运行中',
        notificationText: '系统内录 · 监听「${_config.keyword}」',
        callback: backgroundTaskCallback,
      );
    } catch (e) {
      debugPrint('startService failed: $e');
    }

    if (!mounted) return;
    setState(() {
      _isMonitoring = true;
      _isStarting = false;
      _captureMode = start.mode;
      _hitCount = 0;
      _recentTexts.clear();
      _lastText = '等待识别...（系统内录）';
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
                          _isMonitoring
                              ? (_captureMode == 'playback'
                                  ? '🟢 系统内录'
                                  : '🟡 监控中·$_captureMode')
                              : '⚪ 已停止',
                          style: theme.textTheme.titleMedium,
                        ),
                        Text(
                          '「${_config.keyword}」',
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
            const SizedBox(height: 8),
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
            const SizedBox(height: 8),
            Expanded(
              flex: 2,
              child: Card(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(8, 8, 8, 0),
                      child: Row(
                        children: [
                          Text('识别记录', style: theme.textTheme.labelMedium),
                          const Spacer(),
                          Text(
                            _recentTexts.isEmpty ? '暂无' : '${_recentTexts.length}条',
                            style: theme.textTheme.labelSmall,
                          ),
                        ],
                      ),
                    ),
                    Expanded(
                      child: _recentTexts.isEmpty
                          ? Center(
                              child: Text(
                                '有识别结果会显示在这里',
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
                  ],
                ),
              ),
            ),
            const SizedBox(height: 8),
            Expanded(
              flex: 3,
              child: Card(
                color: Colors.black87,
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(8, 8, 4, 0),
                      child: Row(
                        children: [
                          Text(
                            '运行日志（无需 adb）',
                            style: theme.textTheme.labelMedium?.copyWith(
                              color: Colors.white70,
                            ),
                          ),
                          const Spacer(),
                          TextButton(
                            onPressed: () => setState(_debugLogs.clear),
                            child: const Text('清空', style: TextStyle(fontSize: 12)),
                          ),
                        ],
                      ),
                    ),
                    Expanded(
                      child: _debugLogs.isEmpty
                          ? const Center(
                              child: Text(
                                '等待日志…',
                                style: TextStyle(color: Colors.white38, fontSize: 12),
                              ),
                            )
                          : ListView.builder(
                              controller: _logScroll,
                              padding: const EdgeInsets.all(8),
                              itemCount: _debugLogs.length,
                              itemBuilder: (_, i) {
                                final line = _debugLogs[i];
                                final color = line.contains(' E ')
                                    ? Colors.redAccent
                                    : line.contains(' W ')
                                        ? Colors.orangeAccent
                                        : line.contains('playback') ||
                                                line.contains('ASR ok')
                                            ? Colors.lightGreenAccent
                                            : Colors.white70;
                                return Text(
                                  line,
                                  style: TextStyle(
                                    fontFamily: 'monospace',
                                    fontSize: 10,
                                    color: color,
                                    height: 1.3,
                                  ),
                                );
                              },
                            ),
                    ),
                  ],
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
            const SizedBox(height: 8),
            SizedBox(
              height: 48,
              child: OutlinedButton.icon(
                onPressed: () async {
                  if (_alarm.isRinging) {
                    await _alarm.stop();
                    _appendLog('手动关闭闹铃');
                  } else {
                    final keyword = _config.keyword.isEmpty
                        ? '测试'
                        : _config.keyword;
                    await _alarm.trigger(keyword: keyword, count: 0);
                    _appendLog('手动试响闹铃 keyword=$keyword');
                  }
                  if (mounted) setState(() {});
                },
                icon: Icon(_alarm.isRinging ? Icons.alarm_off : Icons.alarm),
                label: Text(_alarm.isRinging ? '关闭提醒' : '手动试响闹铃'),
                style: OutlinedButton.styleFrom(
                  foregroundColor:
                      _alarm.isRinging ? Colors.red : Colors.deepOrange,
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
