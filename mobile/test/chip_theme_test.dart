import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:smartdrone_mobile/core/theme/app_theme.dart';
import 'package:smartdrone_mobile/features/orders/order_list_screen.dart' show StatusFilterBar;

double contrast(Color a, Color b) {
  final l1 = a.computeLuminance(), l2 = b.computeLuminance();
  return ((l1 > l2 ? l1 : l2) + 0.05) / ((l1 > l2 ? l2 : l1) + 0.05);
}

void main() {
  filterBarTests();
  // Lỗi đã gặp trên emulator: nhãn chip bộ lọc chưa chọn gần như trắng trên nền trắng.
  testWidgets('chip bộ lọc: chữ đọc được (tương phản ≥ 4.5) cả khi chọn và chưa chọn', (tester) async {
    final theme = buildAppTheme();
    await tester.pumpWidget(MaterialApp(
        theme: theme,
        home: const Scaffold(
            body: Row(children: [
          ChoiceChip(key: Key('off'), label: Text('Chưa chọn'), selected: false, onSelected: _noop),
          ChoiceChip(key: Key('on'), label: Text('Đã chọn'), selected: true, onSelected: _noop),
        ]))));
    await tester.pumpAndSettle();

    Color textColor(String text) {
      final rich = tester.widget<RichText>(find.descendant(
          of: find.ancestor(of: find.text(text), matching: find.byType(ChoiceChip)),
          matching: find.byType(RichText)).first);
      return rich.text.style!.color!;
    }

    final chips = theme.chipTheme;
    expect(contrast(textColor('Chưa chọn'), chips.backgroundColor!), greaterThanOrEqualTo(4.5));
    expect(contrast(textColor('Đã chọn'), chips.selectedColor!), greaterThanOrEqualTo(4.5));
  });
}

void _noop(bool _) {}

void filterBarTests() {
  testWidgets('thanh lọc đơn: mọi nhãn chip đều đọc được, kể cả chip chưa chọn', (tester) async {
    await tester.pumpWidget(MaterialApp(
        theme: buildAppTheme(),
        home: Scaffold(
            body: StatusFilterBar(selected: null, onSelected: (_) {}))));
    await tester.pumpAndSettle();
    Color colorOf(String text) {
      final rich = tester.widget<RichText>(find.descendant(
          of: find.ancestor(of: find.text(text), matching: find.byType(ChoiceChip)),
          matching: find.byType(RichText)).first);
      return rich.text.style!.color!;
    }

    // Chip đang chọn: chữ trắng trên nền teal. Chip chưa chọn: chữ đậm trên nền trắng.
    expect(contrast(colorOf('Tất cả'), AppColors.primary), greaterThanOrEqualTo(4.5));
    expect(contrast(colorOf('Chờ duyệt'), AppColors.surface), greaterThanOrEqualTo(4.5));
  });
}
