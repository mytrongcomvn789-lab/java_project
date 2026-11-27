import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/router/app_router.dart';
import 'package:smartdrone_mobile/core/storage/storage_service.dart';
import 'package:smartdrone_mobile/core/theme/app_theme.dart';
import 'package:smartdrone_mobile/features/auth/password_screen.dart';
import 'package:smartdrone_mobile/features/auth/session.dart';
import 'package:smartdrone_mobile/features/orders/delivery_evidence.dart';

import 'support/fakes.dart';

void main() {
  group('quy tắc mật khẩu', () {
    test('mật khẩu mới: độ dài, chữ và số, khác mật khẩu cũ', () {
      expect(PasswordRules.next('', current: 'a'), isNotNull);
      expect(PasswordRules.next('abc123', current: 'x'), contains('8 đến 72'));
      expect(PasswordRules.next('abcdefgh', current: 'x'), contains('chữ cái và 1 chữ số'));
      expect(PasswordRules.next('12345678', current: 'x'), contains('chữ cái và 1 chữ số'));
      expect(PasswordRules.next('Demo@12345', current: 'Demo@12345'), contains('khác'));
      expect(PasswordRules.next('a1' * 40, current: 'x'), contains('8 đến 72'));
      expect(PasswordRules.next('Moi@12345', current: 'Demo@12345'), isNull);
    });
    test('nhập lại phải khớp', () {
      expect(PasswordRules.confirm('a', next: 'b'), isNotNull);
      expect(PasswordRules.confirm('b', next: 'b'), isNull);
    });
  });

  group('Session.changePassword', () {
    Future<(Session, FakeAdapter, MemoryTokens)> signedIn(
        FutureOr<ResponseBody> Function(RequestOptions) onApi) async {
      final store = MemoryTokens()..value = const Tokens('a', 'r');
      final api = FakeAdapter((o) {
        if (o.path == '/v1/me' && o.method == 'GET') return jsonResponse(profile());
        return onApi(o);
      });
      final s = makeSession(store: store, api: api, auth: FakeAdapter((_) => jsonResponse({})));
      await s.restore();
      return (s, api, store);
    }

    test('thành công: gửi đúng nội dung rồi đăng xuất và xóa token cục bộ', () async {
      final (s, api, store) = await signedIn((_) => ResponseBody.fromString('', 204));
      addTearDown(s.dispose);
      expect(s.phase, SessionPhase.signedIn);
      await s.changePassword('Cu@12345', 'Moi@12345');
      final post = api.requests.firstWhere((r) => r.path == '/v1/me/password');
      expect(post.data, {'currentPassword': 'Cu@12345', 'newPassword': 'Moi@12345'});
      expect(s.phase, SessionPhase.signedOut);
      expect(store.value, isNull);
    });

    test('mật khẩu hiện tại sai (400): giữ nguyên phiên đăng nhập và ném lỗi', () async {
      final (s, _, store) = await signedIn((_) => jsonResponse(
          {'code': 'INVALID_CURRENT_PASSWORD', 'message': 'Mật khẩu hiện tại không đúng'}, 400));
      addTearDown(s.dispose);
      await expectLater(s.changePassword('sai', 'Moi@12345'), throwsA(isA<DioException>()));
      expect(s.phase, SessionPhase.signedIn);
      expect(store.value, isNotNull);
    });
  });

  testWidgets('màn đổi mật khẩu: kiểm tra nhập; không gửi khi sai; gửi một lần khi hợp lệ', (tester) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    final store = MemoryTokens()..value = const Tokens('a', 'r');
    final api = FakeAdapter((o) =>
        o.path == '/v1/me' ? jsonResponse(profile()) : ResponseBody.fromString('', 204));
    final session = makeSession(store: store, api: api, auth: FakeAdapter((_) => jsonResponse({})));
    await tester.runAsync(session.restore);
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (_, __) => const ChangePasswordScreen()),
      GoRoute(path: '/profile', builder: (_, __) => const Scaffold(body: Text('PROFILE'))),
    ]);
    await tester.pumpWidget(ProviderScope(
        overrides: [sessionProvider.overrideWith((ref) => session)],
        child: MaterialApp.router(theme: buildAppTheme(), routerConfig: router)));
    await tester.pumpAndSettle();

    await tester.tap(find.byKey(const Key('password-submit')));
    await tester.pumpAndSettle();
    expect(find.text('Nhập mật khẩu hiện tại.'), findsOneWidget);
    expect(api.requests.where((r) => r.path == '/v1/me/password'), isEmpty);

    await tester.enterText(find.byKey(const Key('password-current')), 'Cu@12345');
    await tester.enterText(find.byKey(const Key('password-new')), 'Moi@12345');
    await tester.enterText(find.byKey(const Key('password-confirm')), 'Khac@12345');
    await tester.tap(find.byKey(const Key('password-submit')));
    await tester.pumpAndSettle();
    expect(find.text('Mật khẩu nhập lại không khớp.'), findsOneWidget);
    expect(api.requests.where((r) => r.path == '/v1/me/password'), isEmpty);

    await tester.enterText(find.byKey(const Key('password-confirm')), 'Moi@12345');
    await tester.tap(find.byKey(const Key('password-submit')));
    await tester.pumpAndSettle();
    expect(api.requests.where((r) => r.path == '/v1/me/password').length, 1);
    expect(session.phase, SessionPhase.signedOut);
  });

  test('định tuyến: /profile/password cho cả các vai trò đã đăng nhập', () async {
    for (final role in ['CUSTOMER', 'STATION_OPERATOR', 'DISPATCHER', 'LOGISTICS_MANAGER']) {
      final session = makeSession(
          store: MemoryTokens(),
          api: FakeAdapter((_) => jsonResponse({})),
          auth: FakeAdapter((_) => jsonResponse(tokenResponse(role: role))));
      addTearDown(session.dispose);
      await session.login('a@test.com', 'secret');
      expect(sessionRedirect(session, '/profile/password'), isNull, reason: role);
    }
  });

  group('bằng chứng giao hàng', () {
    const oid = '11111111-1111-1111-1111-111111111111';
    Map<String, dynamic> conf() => {
          'id': 'c1',
          'orderId': oid,
          'orderVersion': 6,
          'method': 'OTP',
          'confirmedBy': 'u',
          'stationId': 's',
          'photoFileId': 'p1',
          'note': 'Đã giao tận tay',
          'confirmedAt': '2026-11-27T07:01:43Z',
        };

    Future<void> mount(WidgetTester tester, FakeAdapter api) async {
      tester.view.physicalSize = const Size(1170, 2532);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      final session = makeSession(
          store: MemoryTokens()..value = const Tokens('a', 'r'),
          api: api,
          auth: FakeAdapter((_) => jsonResponse({})));
      await tester.runAsync(session.restore);
      await tester.pumpWidget(ProviderScope(
          overrides: [sessionProvider.overrideWith((ref) => session)],
          child: MaterialApp(
              theme: buildAppTheme(),
              home: const Scaffold(body: SingleChildScrollView(child: DeliveryEvidenceCard(orderId: oid))))));
      await tester.pumpAndSettle();
    }

    test('nhãn phương thức', () {
      expect(confirmMethodLabel('OTP'), 'Mã OTP');
      expect(confirmMethodLabel('QR'), 'Mã QR');
      expect(confirmMethodLabel('X'), 'X');
    });

    testWidgets('hiện phương thức, ghi chú và xin liên kết ảnh đúng tệp', (tester) async {
      final api = FakeAdapter((o) {
        if (o.path == '/v1/me') return jsonResponse(profile());
        if (o.path.endsWith('/confirmation')) return jsonResponse(conf());
        if (o.path == '/v1/files/p1/download-link') {
          return jsonResponse({'fileId': 'p1', 'url': 'http://localhost/none.png', 'expiresAt': '2026-11-27T08:00:00Z'});
        }
        return jsonResponse({}, 404);
      });
      await mount(tester, api);
      expect(find.text('Mã OTP'), findsOneWidget);
      expect(find.text('Đã giao tận tay'), findsOneWidget);
      expect(find.byKey(const Key('evidence-photo')), findsOneWidget);
      expect(api.requests.any((r) => r.path == '/v1/files/p1/download-link'), isTrue);
    });

    testWidgets('không xin được liên kết ảnh: vẫn hiện phần chữ, báo không có ảnh', (tester) async {
      final api = FakeAdapter((o) {
        if (o.path == '/v1/me') return jsonResponse(profile());
        if (o.path.endsWith('/confirmation')) return jsonResponse(conf());
        return jsonResponse({'code': 'X', 'message': 'lỗi'}, 500);
      });
      await mount(tester, api);
      expect(find.text('Mã OTP'), findsOneWidget);
      expect(find.byKey(const Key('evidence-no-photo')), findsOneWidget);
      expect(find.byKey(const Key('evidence-photo')), findsNothing);
    });

    testWidgets('lỗi tải bằng chứng: báo lỗi và Thử lại phục hồi', (tester) async {
      var fail = true;
      final api = FakeAdapter((o) {
        if (o.path == '/v1/me') return jsonResponse(profile());
        if (o.path.endsWith('/confirmation')) {
          return fail ? jsonResponse({'code': 'X', 'message': 'Chưa có bằng chứng'}, 404) : jsonResponse(conf());
        }
        return jsonResponse({'fileId': 'p1', 'url': null, 'expiresAt': 'x'});
      });
      await mount(tester, api);
      expect(find.byKey(const Key('evidence-error')), findsOneWidget);
      fail = false;
      await tester.tap(find.byKey(const Key('evidence-retry')));
      await tester.pumpAndSettle();
      expect(find.text('Mã OTP'), findsOneWidget);
    });
  });
}
