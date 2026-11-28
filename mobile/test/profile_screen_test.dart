import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/storage/storage_service.dart';
import 'package:smartdrone_mobile/core/theme/app_theme.dart';
import 'package:smartdrone_mobile/features/auth/screens.dart';
import 'package:smartdrone_mobile/features/auth/session.dart';

import 'support/fakes.dart';

void main() {
  Future<void> mount(WidgetTester tester, Widget screen,
      {String role = 'CUSTOMER', String name = 'Nguyễn Văn An'}) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    final session = makeSession(
        store: MemoryTokens()..value = const Tokens('a', 'r'),
        api: FakeAdapter((_) => jsonResponse(profile(role: role, name: name))),
        auth: FakeAdapter((_) => jsonResponse({})));
    await tester.runAsync(session.restore);
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (_, __) => screen),
      GoRoute(
          path: '/customer/addresses', builder: (_, __) => const Scaffold(body: Text('ADDRESSES'))),
      GoRoute(path: '/customer', builder: (_, __) => const Scaffold(body: Text('HOME'))),
      GoRoute(path: '/operator', builder: (_, __) => const Scaffold(body: Text('OP'))),
    ]);
    await tester.pumpWidget(ProviderScope(
        overrides: [sessionProvider.overrideWith((ref) => session)],
        child: MaterialApp.router(theme: buildAppTheme(), routerConfig: router)));
    await tester.pumpAndSettle();
  }

  test('tên vai trò hiển thị bằng tiếng Việt', () {
    expect(roleLabel('CUSTOMER'), 'Khách hàng');
    expect(roleLabel('STATION_OPERATOR'), 'Nhân viên trạm');
    expect(roleLabel('DISPATCHER'), 'Điều phối viên');
    expect(roleLabel('UNKNOWN_X'), 'UNKNOWN_X');
  });

  testWidgets('vai trò chưa hỗ trợ: nói rõ vai trò hiện tại và chỉ cho đăng xuất', (tester) async {
    await mount(tester, const UnsupportedScreen(), role: 'SYSTEM_ADMIN');
    expect(find.byKey(const Key('unsupported-title')), findsOneWidget);
    expect(find.textContaining('Quản trị hệ thống'), findsOneWidget);
    expect(find.textContaining('ứng dụng web'), findsOneWidget);
    expect(find.byKey(const Key('unsupported-logout')), findsOneWidget);
  });

  testWidgets('hồ sơ khách: thẻ đầu trang, vai trò, sổ địa chỉ dẫn tới màn địa chỉ', (tester) async {
    await mount(tester, const ProfileScreen());
    expect(find.byKey(const Key('profile-header')), findsOneWidget);
    expect(find.text('Nguyễn Văn An'), findsWidgets);
    expect(find.text('N'), findsOneWidget); // chữ cái đầu
    expect(find.text('Khách hàng'), findsOneWidget);
    await tester.tap(find.byKey(const Key('profile-addresses')));
    await tester.pumpAndSettle();
    expect(find.text('ADDRESSES'), findsOneWidget);
  });

  testWidgets('hồ sơ nhân viên trạm: không có sổ địa chỉ của khách', (tester) async {
    await mount(tester, const ProfileScreen(), role: 'STATION_OPERATOR');
    expect(find.text('Nhân viên trạm'), findsOneWidget);
    expect(find.byKey(const Key('profile-addresses')), findsNothing);
  });

  testWidgets('số điện thoại sai thì báo lỗi và không lưu', (tester) async {
    await mount(tester, const ProfileScreen());
    await tester.enterText(find.byKey(const Key('profile-phone')), 'abc');
    await tester.tap(find.byKey(const Key('profile-save')));
    await tester.pumpAndSettle();
    expect(find.textContaining('Nhập 9–15 chữ số'), findsOneWidget);
    expect(find.byKey(const Key('profile-message')), findsNothing);
  });

  testWidgets('lưu thành công: hiện thông báo xanh', (tester) async {
    await mount(tester, const ProfileScreen());
    await tester.enterText(find.byKey(const Key('profile-name')), 'Trần Thị Bình');
    await tester.tap(find.byKey(const Key('profile-save')));
    await tester.pumpAndSettle();
    expect(find.text('Đã lưu hồ sơ.'), findsOneWidget);
  });
}
