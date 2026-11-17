import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:smartdrone_mobile/main.dart';

void main() {
  testWidgets('Home renders inside the application ProviderScope', (tester) async {
    // Match main(): HomePage is a ConsumerWidget and requires ProviderScope.
    await tester.pumpWidget(const ProviderScope(child: MyApp()));
    await tester.pumpAndSettle();

    expect(find.text('SmartDroneDelivery - Mobile App'), findsOneWidget);
    expect(tester.takeException(), isNull);
  });
}