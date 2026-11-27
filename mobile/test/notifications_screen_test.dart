import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_provider.dart';
import 'package:smartdrone_mobile/core/theme/app_theme.dart';
import 'package:smartdrone_mobile/features/notifications/notification_screens.dart';
import 'package:smartdrone_mobile/features/orders/order_models.dart';
import 'package:smartdrone_mobile/features/orders/orders_api.dart';

AppNotification note(String id, String status, {bool read = false, String? title}) => AppNotification(
    id: 'n-$id',
    eventId: id,
    orderId: 'order-$id',
    title: title ?? 'Tiêu đề $id',
    body: 'Nội dung $id',
    createdAt: DateTime(2026, 11, 26, 9, int.parse(id)),
    readAt: read ? DateTime(2026, 11, 26, 10) : null,
    status: status);

class FakeNotifApi extends OrdersApi {
  FakeNotifApi(this.items) : super(Dio());
  List<AppNotification> items;
  bool fail = false;
  final read = <String>[];
  int readAll = 0;
  @override
  Future<List<AppNotification>> notifications() async {
    if (fail) throw DioException.connectionError(requestOptions: RequestOptions(), reason: 'off');
    return items;
  }

  @override
  Future<void> markRead(String notificationId) async => read.add(notificationId);
  @override
  Future<void> markAllRead() async => readAll++;
}

void main() {
  final list = [
    note('1', 'DELIVERED'),
    note('2', 'FAILED', title: 'Giao thất bại'),
    note('3', 'APPROVED', read: true),
    note('4', 'IN_TRANSIT', read: true),
  ];

  test('bộ lọc phân loại theo trạng thái đơn', () {
    expect([for (final n in list) if (NotificationFilter.delivery.matches(n)) n.eventId], ['1', '4']);
    expect([for (final n in list) if (NotificationFilter.issue.matches(n)) n.eventId], ['2']);
    expect([for (final n in list) if (NotificationFilter.unread.matches(n)) n.eventId], ['1', '2']);
    expect(list.every(NotificationFilter.all.matches), isTrue);
    expect(NotificationFilter.issue.matches(note('9', 'X')), isFalse);
  });

  group('màn thông báo', () {
    late FakeNotifApi api;

    Future<void> mount(WidgetTester tester, List<AppNotification> items, {bool fail = false}) async {
      tester.view.physicalSize = const Size(1170, 2532);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      api = FakeNotifApi(items)..fail = fail;
      final router = GoRouter(routes: [
        GoRoute(path: '/', builder: (_, __) => const NotificationsScreen()),
        GoRoute(path: '/customer', builder: (_, __) => const Scaffold(body: Text('HOME'))),
        GoRoute(
            path: '/customer/orders/:id',
            builder: (_, s) => Scaffold(body: Text('TRACK ${s.pathParameters['id']}'))),
      ]);
      await tester.pumpWidget(ProviderScope(
          overrides: [
            ordersApiProvider.overrideWithValue(api),
            realtimeClientProvider.overrideWith((ref) => null),
            realtimeUserProvider.overrideWithValue('u1'),
          ],
          child: MaterialApp.router(theme: buildAppTheme(), routerConfig: router)));
      await tester.pumpAndSettle();
    }

    testWidgets('mở màn là tải và hiện thông báo, chấm chưa đọc đúng chỗ', (tester) async {
      await mount(tester, list);
      expect(find.byKey(const Key('notification-1')), findsOneWidget);
      expect(find.byKey(const Key('notification-4')), findsOneWidget);
      expect(find.byKey(const Key('unread-dot')), findsNWidgets(2));
    });

    testWidgets('lọc "Sự cố" chỉ còn thông báo thất bại, có nút Sắp có đã tắt', (tester) async {
      await mount(tester, list);
      await tester.tap(find.byKey(const Key('filter-issue')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('notification-2')), findsOneWidget);
      expect(find.byKey(const Key('notification-1')), findsNothing);
      expect(tester.widget<OutlinedButton>(find.byKey(const Key('action-redeliver'))).onPressed, isNull);
      expect(tester.widget<OutlinedButton>(find.byKey(const Key('action-return'))).onPressed, isNull);
    });

    testWidgets('mục không có thông báo thì hiện trạng thái rỗng của mục đó', (tester) async {
      await mount(tester, [note('3', 'APPROVED', read: true)]);
      await tester.tap(find.byKey(const Key('filter-unread')));
      await tester.pumpAndSettle();
      expect(find.text('Không có thông báo trong mục này'), findsOneWidget);
    });

    testWidgets('không có thông báo nào: rỗng có hướng dẫn', (tester) async {
      await mount(tester, []);
      expect(find.byKey(const Key('state-empty')), findsOneWidget);
      expect(find.text('Chưa có thông báo nào'), findsOneWidget);
    });

    testWidgets('mất mạng: báo lỗi, có Thử lại và phục hồi được', (tester) async {
      await mount(tester, list, fail: true);
      expect(find.byKey(const Key('state-error')), findsOneWidget);
      api.fail = false;
      await tester.tap(find.byKey(const Key('state-retry')).first);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('notification-1')), findsOneWidget);
    });

    testWidgets('bấm thông báo: đánh dấu đã đọc rồi mở màn theo dõi đúng đơn', (tester) async {
      await mount(tester, list);
      await tester.tap(find.byKey(const Key('notification-1')));
      await tester.pumpAndSettle();
      expect(api.read, ['n-1']);
      expect(find.text('TRACK order-1'), findsOneWidget);
    });

    testWidgets('"Đọc tất cả" gọi API một lần rồi bị tắt', (tester) async {
      await mount(tester, list);
      await tester.tap(find.byKey(const Key('mark-all-read')));
      await tester.pumpAndSettle();
      expect(api.readAll, 1);
      expect(find.byKey(const Key('unread-dot')), findsNothing);
      expect(tester.widget<TextButton>(find.byKey(const Key('mark-all-read'))).onPressed, isNull);
    });
  });
}
