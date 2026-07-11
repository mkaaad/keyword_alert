# keyword_alert

腾讯会议 / B站等场景下的**关键词频率监控**（Flutter + 离线 SenseVoice ASR）。

时间窗口内关键词（默认「老师」）出现达到阈值时，响铃并通知。

## 两种发布包

| 产物 | 安装方式 | Root | 说明 |
|------|----------|------|------|
| **keyword_alert-user.apk** | 普通安装 | **不需要** | 开始监控时允许「屏幕录制/投射」，用 AudioPlaybackCapture 抓其它 App 播放声 |
| **keyword_alert_magisk.zip** | Magisk 模块 | 需要 Magisk | priv-app + 预解压 `lib/arm64`；可选系统权限 / REMOTE 兜底 |

GitHub Actions 在打 `v*` 标签时会把两个文件都挂到 Release；`workflow_dispatch` 会上传同名 artifact。

## 功能

- **优先**：MediaProjection + AudioPlaybackCapture（系统播放内录）
- **兜底**：REMOTE_SUBMIX / 麦克风（视权限与设备）
- 离线 SenseVoice（sherpa-onnx）中文识别
- 可配置关键词 / 阈值 / 时间窗口
- 前台服务保活 + 本地通知 + 循环铃声

## 环境

- Android 8.0+（minSdk 26）；内录建议 Android 10+
- 构建：Flutter 3.29+ / Dart 3.7+

## 安装使用

### 普通版（user APK）

1. 安装 `keyword_alert-user.apk`
2. 授予录音、通知权限
3. 点「开始监控」→ **允许屏幕录制/投射**
4. 打开会议 / B站等有声内容

### Magisk 版

1. Magisk 安装 `keyword_alert_magisk.zip`，重启  
   （模块内必须有 `system/priv-app/KeywordAlert/lib/arm64/*.so`，否则会闪退）
2. 打开 App，按提示授权
3. 开始监控（同样建议允许投射内录）

## 本地构建

```bash
bash setup.sh          # 下载 model.int8.onnx + tokens.txt
bash pack.sh           # analyze + test + 产出两个包
# keyword_alert-user.apk
# keyword_alert_magisk.zip
```

## 测试

```bash
flutter test
flutter analyze
```

## 项目结构

```
lib/                    # Flutter UI / 监控逻辑
android/.../MainActivity.kt   # 投射内录 + REMOTE_SUBMIX
android/.../AsrStreamHandler.kt
scripts/package_magisk.sh     # APK + lib/arm64 → Magisk zip
magisk_module/
```

## 注意

- 模型体积大（约 200MB+），`setup.sh` 从 GitHub Release 下载
- 部分 App 可禁止被 AudioPlaybackCapture 捕获
- 当前 release 默认 debug 签名，仅便于自用分发
