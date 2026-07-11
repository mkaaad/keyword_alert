import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:keyword_alert/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  testWidgets('App renders home page', (WidgetTester tester) async {
    await tester.pumpWidget(const MainApp());
    // Allow async init (SharedPreferences / AlarmService) to settle.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('关键词监控'), findsOneWidget);
    expect(find.textContaining('开始监控'), findsOneWidget);
    expect(find.textContaining('老师'), findsWidgets);
  });
}
