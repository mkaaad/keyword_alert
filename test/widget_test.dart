import "package:flutter_test/flutter_test.dart";
import "package:keyword_alert/main.dart";

void main() {
  testWidgets("App renders", (WidgetTester tester) async {
    await tester.pumpWidget(const MainApp());
    expect(find.text("关键词监控"), findsOneWidget);
  });
}
