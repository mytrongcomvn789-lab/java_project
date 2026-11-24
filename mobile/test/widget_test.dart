import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:dio/dio.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_provider.dart';
import 'package:smartdrone_mobile/features/auth/session.dart';
import 'package:smartdrone_mobile/main.dart';
import 'support/fakes.dart';

void main() {
  Future<void> mount(WidgetTester tester, Session session) async {
    await session.restore();
    await tester.pumpWidget(ProviderScope(overrides: [
      sessionProvider.overrideWith((ref) => session),
      realtimeClientProvider.overrideWith((ref) => null), // no real socket in widget tests
    ], child: const MyApp()));
    await tester.pumpAndSettle();
  }

  testWidgets('login validates email/password without sending network request',
      (tester) async {
    final auth = FakeAdapter((_) => jsonResponse({}));
    await mount(
        tester, makeSession(store: MemoryTokens(), api: auth, auth: auth));
    await tester.tap(find.widgetWithText(FilledButton, 'Đăng nhập'));
    await tester.pumpAndSettle();
    expect(find.text('Nhập địa chỉ email hợp lệ.'), findsOneWidget);
    expect(find.text('Nhập mật khẩu.'), findsOneWidget);
    expect(auth.requests, isEmpty);
  });

  testWidgets('busy login blocks double tap then shows customer workspace',
      (tester) async {
    final gate = Completer<ResponseBody>();
    final auth = FakeAdapter((_) => gate.future);
    await mount(
        tester,
        makeSession(
            store: MemoryTokens(),
            api: FakeAdapter((_) => jsonResponse({})),
            auth: auth));
    await tester.enterText(find.byType(TextFormField).at(0), 'a@test.com');
    await tester.enterText(find.byType(TextFormField).at(1), 'secret');
    await tester.tap(find.widgetWithText(FilledButton, 'Đăng nhập'));
    await tester.pump();
    expect(tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNull);
    await tester.pump(const Duration(milliseconds: 30));
    expect(auth.requests.length, 1);
    gate.complete(jsonResponse(tokenResponse()));
    await tester.pumpAndSettle();
    expect(find.text('Đơn hàng của tôi'), findsOneWidget);
    expect(find.byKey(const Key('open-notifications')), findsOneWidget);
  });

  testWidgets('wrong password displays error and allows retry', (tester) async {
    final auth = FakeAdapter((_) => jsonResponse({}, 401));
    await mount(
        tester,
        makeSession(
            store: MemoryTokens(),
            api: FakeAdapter((_) => jsonResponse({})),
            auth: auth));
    await tester.enterText(find.byType(TextFormField).at(0), 'a@test.com');
    await tester.enterText(find.byType(TextFormField).at(1), 'wrong');
    await tester.tap(find.widgetWithText(FilledButton, 'Đăng nhập'));
    await tester.pumpAndSettle();
    expect(find.textContaining('không hợp lệ.'), findsOneWidget);
    expect(tester.widget<FilledButton>(find.byType(FilledButton)).onPressed,
        isNotNull);
  });
}
