# keyword_alert

腾讯会议等场景下的**关键词频率监控** App（Flutter + 原生音频捕获 + 离线 ASR）。

当系统音频中检测到配置的关键词（默认「老师」）在时间窗口内出现达到阈值次数时，播放闹钟并发送通知。

## 功能

- Root + Magisk 注入 `REMOTE_SUBMIX`，捕获系统播放音频（非麦克风）
- 离线 SenseVoice（sherpa-onnx）中文识别
- 可配置关键词 / 阈值 / 时间窗口
- 前台服务保活 + 本地通知 + 循环铃声

## 环境要求

- Root 设备 + Magisk
- Android 8.0+（minSdk 26）
- Flutter 3.29+ / Dart 3.7+

## 本地构建

```bash
# 1. 下载 ASR 模型（model.int8.onnx + tokens.txt）
bash setup.sh

# 2. 一键打 Magisk 模块包
bash pack.sh
# 产物: keyword_alert_magisk.zip
```

或仅构建 APK：

```bash
bash setup.sh
flutter pub get
flutter build apk --release
```

## 安装使用

1. Magisk 安装 `keyword_alert_magisk.zip`，重启
2. 打开「关键词监控」，授予 Root
3. （可选）点设置改关键词/阈值/窗口
4. 点「开始监控」，保持会议有声音输出

## 测试

```bash
flutter test
flutter analyze
```

## 项目结构

```
lib/
  main.dart                 # 入口 + MaterialApp
  models/monitor_config.dart
  services/
    audio_capture.dart      # Method/EventChannel 封装
    keyword_monitor.dart    # 滑动窗口计数
    alarm_service.dart      # 铃声 + 通知
    background_task.dart    # 前台服务回调
  ui/home_page.dart
android/.../MainActivity.kt # REMOTE_SUBMIX 采集
android/.../AsrStreamHandler.kt  # SenseVoice 离线识别
magisk_module/              # priv-app + 音频策略注入
```

## 注意

- 需要 `CAPTURE_AUDIO_OUTPUT`（priv-app 权限白名单）
- 模型约 200MB+，`setup.sh` 会从 GitHub Release 下载
- 未 Root 时会启动失败并提示原因
