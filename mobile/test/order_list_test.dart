import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_provider.dart';
import 'package:smartdrone_mobile/core/storage/storage_service.dart';
import 'package:smartdrone_mobile/features/auth/session.dart';
import 'package:smartdrone_mobile/features/orders/customer_order_models.dart';
import 'package:smartdrone_mobile/features/orders/customer_orders_api.dart';
import 'package:smartdrone_mobile/features/orders/order_list_controller.dart';
import 'package:smartdrone_mobile/features/orders/order_list_screen.dart';

import 'support/fakes.dart';

OrderSummary order(int i, {String status = 'PENDING'}) => OrderSummary(
    id: '${i.toString().padLeft(8, '0')}-0000-0000-0000-000000000000',
    status: status,
    statusLabel: status == 'PENDING' ? 'Chờ duyệt' : 'Đã duyệt',
    pickupStationId: 's1',
    dropoffStationId: 's2',
    recipientName: 'Người $i',
    recipientAddressLine: 'Địa chỉ $i',
    packageDescription: 'Gói $i');

/// Server giả: [total] đơn, lọc theo trạng thái, có thể chặn/ném lỗi từng lần gọi.
class FakeListApi extends CustomerOrdersApi {
  FakeListApi({this.total = 45}) : super(Dio());
  int total;
  final calls = <({int page, String? status})>[];
  Completer<void>? gate; // chặn phản hồi cho tới khi hoàn tất
  Object? failNext;

  @override
  Future<OrderPage> listOrders(
      {int page = 1,
      int pageSize = 20,
      String? status,
      DateTime? from,
      DateTime? to}) async {
    calls.add((page: page, status: status));
    final g = gate;
    if (g != null) await g.future;
    final f = failNext;
    if (f != null) {
      failNext = null;
      throw f;
    }
    final all = [
      for (var i = 1; i <= total; i++) order(i, status: status ?? 'PENDING')
    ];
    final from0 = (page - 1) * pageSize;
    final items = from0 >= all.length
        ? <OrderSummary>[]
        : all.sublist(from0, (from0 + pageSize).clamp(0, all.length));
    return OrderPage(items: items, page: page, pageSize: pageSize, total: total);
  }
}

Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 10));

void main() {
  late FakeListApi api;
  late StateProvider<String?> user;
  late ProviderContainer container;

  setUp(() {
    api = FakeListApi();
    user = StateProvider<String?>((ref) => 'user-1');
    container = ProviderContainer(overrides: [
      customerOrdersApiProvider.overrideWithValue(api),
      realtimeUserProvider.overrideWith((ref) => ref.watch(user)),
      realtimeClientProvider.overrideWith((ref) => null),
    ]);
    addTearDown(container.dispose);
  });

  OrderListState read() => container.read(orderListProvider);
  Future<void> boot() async {
    container.listen(orderListProvider, (_, __) {}); // giữ provider sống
    await settle();
  }

  test('tải trang 1 rồi phân trang tới hết, không trùng đơn', () async {
    await boot();
    expect(read().items.length, 20);
    expect(read().hasMore, isTrue);
    final c = container.read(orderListProvider.notifier);
    await c.loadMore();
    await c.loadMore();
    expect(read().items.length, 45);
    expect(read().items.map((e) => e.id).toSet().length, 45);
    expect(read().hasMore, isFalse);
    await c.loadMore(); // hết trang: không gọi thêm
    expect(api.calls.map((e) => e.page), [1, 2, 3]);
  });

  test('đang tải thêm thì bỏ qua lần loadMore trùng', () async {
    await boot();
    api.gate = Completer<void>();
    final c = container.read(orderListProvider.notifier);
    final a = c.loadMore();
    final b = c.loadMore();
    api.gate!.complete();
    await Future.wait([a, b]);
    expect(api.calls.where((e) => e.page == 2).length, 1);
    expect(read().items.length, 40);
  });

  test('đổi bộ lọc bắt đầu lại từ trang 1 và bỏ phản hồi cũ đến muộn', () async {
    await boot();
    final c = container.read(orderListProvider.notifier);
    api.gate = Completer<void>();
    final slow = c.setStatus('PENDING'); // không đổi: bỏ qua
    final first = c.setStatus('APPROVED');
    await settle();
    final gate1 = api.gate!;
    api.gate = Completer<void>();
    final second = c.setStatus('CANCELLED');
    gate1.complete(); // phản hồi của APPROVED về sau khi đã đổi sang CANCELLED
    await first;
    expect(read().status, 'CANCELLED');
    expect(read().items, isEmpty); // chưa nhận kết quả CANCELLED, không lẫn APPROVED
    api.gate!.complete();
    await Future.wait([slow, second]);
    expect(read().status, 'CANCELLED');
    expect(read().items.every((e) => e.status == 'CANCELLED'), isTrue);
    expect(read().items.length, 20);
  });

  test('lỗi trang đầu: hiện lỗi, thử lại được', () async {
    api.failNext = DioException.connectionError(
        requestOptions: RequestOptions(), reason: 'offline');
    await boot();
    expect(read().error?.kind, FailureKind.offline);
    expect(read().items, isEmpty);
    await container.read(orderListProvider.notifier).reload();
    expect(read().error, isNull);
    expect(read().items.length, 20);
  });

  test('lỗi khi làm mới giữ nguyên danh sách đã có', () async {
    await boot();
    api.failNext = DioException.connectionError(
        requestOptions: RequestOptions(), reason: 'offline');
    await container.read(orderListProvider.notifier).reload();
    expect(read().items.length, 20);
    expect(read().error, isNull);
    expect(read().moreError?.kind, FailureKind.offline);
  });

  test('lỗi tải thêm giữ trang đã có và cho thử lại', () async {
    await boot();
    final c = container.read(orderListProvider.notifier);
    api.failNext = DioException.connectionError(
        requestOptions: RequestOptions(), reason: 'offline');
    await c.loadMore();
    expect(read().items.length, 20);
    expect(read().moreError, isNotNull);
    await c.loadMore();
    expect(read().items.length, 40);
    expect(read().moreError, isNull);
  });

  test('đổi tài khoản xóa danh sách cũ và tải lại cho user mới', () async {
    await boot();
    expect(read().items.length, 20);
    api.total = 3;
    api.gate = Completer<void>();
    container.read(user.notifier).state = 'user-2';
    await settle();
    expect(read().items, isEmpty); // không còn thấy đơn của user trước
    expect(read().loading, isTrue);
    api.gate!.complete();
    await settle();
    expect(read().items.length, 3);
  });

  testWidgets('màn hình: danh sách, chip lọc, trạng thái rỗng', (tester) async {
    final store = MemoryTokens()..value = const Tokens('a', 'r');
    final session = makeSession(
        store: store,
        api: FakeAdapter((_) => jsonResponse(profile())),
        auth: FakeAdapter((_) => jsonResponse({})));
    await tester.runAsync(session.restore);
    final small = FakeListApi(total: 2);
    await tester.pumpWidget(ProviderScope(
        overrides: [
          sessionProvider.overrideWith((ref) => session),
          customerOrdersApiProvider.overrideWithValue(small),
          realtimeClientProvider.overrideWith((ref) => null),
        ],
        child: const MaterialApp(home: CustomerHomeScreen())));
    await tester.pumpAndSettle();
    expect(find.text('Đơn 00000001'), findsOneWidget);
    expect(find.text('Gói 2', findRichText: true), findsNothing); // nằm trong subtitle nhiều dòng
    expect(find.textContaining('Người nhận: Người 1'), findsOneWidget);
    await tester.tap(find.byKey(const Key('filter-APPROVED')));
    await tester.pumpAndSettle();
    expect(small.calls.last.status, 'APPROVED');
    small.total = 0;
    await tester.scrollUntilVisible(find.byKey(const Key('filter-CANCELLED')), 200,
        scrollable: find.byType(Scrollable).first);
    await tester.tap(find.byKey(const Key('filter-CANCELLED')));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('list-empty')), findsOneWidget);
    expect(find.textContaining('Đã hủy'), findsWidgets);
  });
}
