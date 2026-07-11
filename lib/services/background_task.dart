import 'package:flutter_foreground_task/flutter_foreground_task.dart';

/// Top-level entry point required by flutter_foreground_task.
@pragma('vm:entry-point')
void backgroundTaskCallback() {
  FlutterForegroundTask.setTaskHandler(BackgroundTaskHandler());
}

class BackgroundTaskHandler extends TaskHandler {
  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {}

  @override
  void onRepeatEvent(DateTime timestamp) {}

  @override
  Future<void> onDestroy(DateTime timestamp) async {}
}
