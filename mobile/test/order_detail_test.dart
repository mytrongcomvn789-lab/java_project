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
import 'package:smartdrone_mobile/features/orders/order_detail_controller.dart';
import 'package:smartdrone_mobile/features/orders/order_detail_screen.dart';

import 'support/fakes.dart';

const id = '11111111-1111-1111-1111-111111111111';

OrderDetail detail(String status, int version) => OrderDetail.fromJson({
      'id': id,
      'version': version,
      'status': status,
      'statusLabel': {'PENDING': 'Chờ duyệt', 'APPROVED': 'Đã duyệt', 'CANCELLED': 'Đã hủy'}[status],
      'pickupStationId': 's1',
      'dropoffStationId': 's2',
      'recipient': {
        'name': 'An',
        'phone': '0901234567',
        'addressLine': '1 Lê Lợi',
        'latitude': 1,
        'longitude': 2
      },
      'package': {
        'description': 'Hộp quà',
        'weightGrams': 1200,
        'lengthCm': 30,
        'widthCm': 20,
        'heightCm': 10,
        'photoFileIds': ['f1', 'f2']
      },
      'history': [
        {'eventId': 'e$version', 'orderVersion': version, 'toStatus': status}
      ],
    });

DioException http(int status, String code) => DioException(
    requestOptions: RequestOptions(path: '/x'),
    response: Response(
        requestOptions: RequestOptions(path: '/x'),
        statusCode: status,
        data: {'code': code, 'message': 'msg $code'}));

/// Server giả giữ một đơn; `cancelBehavior` quyết định kết quả của lần hủy.
class FakeDetailApi extends CustomerOrdersApi {
  FakeDetailApi() : super(Dio());
  OrderDetail current = detail('PENDING', 0);
  Object? getError;
  Future<OrderDetail> Function(int version)? cancelBehavior;
  final cancelVersions = <int>[];
  int gets = 0;

  @override
  Future<OrderDetail> getOrder(String orderId) async {
    gets++;
    final e = getError;
    if (e != null) throw e;
    return current;
  }

  @override
  Future<OrderDetail> cancelOrder(String orderId, int version) async {
    cancelVersions.add(version);
    return cancelBehavior!(version);
  }

  @override
  Future<List<Station>> listStations() async => const [
        Station(id: 's1', name: 'Trạm Bắc', latitude: 0, longitude: 0, status: 'ACTIVE'),
        Station(id: 's2', name: 'Trạm Nam', latitude: 0, longitude: 0, status: 'ACTIVE'),
      ];
}

Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 10));

void main() {
  late FakeDetailApi api;
  late StateProvider<String?> user;
  late ProviderContainer container;

  setUp(() {
    api = FakeDetailApi();
    user = StateProvider<String?>((ref) => 'user-1');
    container = ProviderContainer(overrides: [
      customerOrdersApiProvider.overrideWithValue(api),
      realtimeUserProvider.overrideWith((ref) => ref.watch(user)),
      realtimeClientProvider.overrideWith((ref) => null),
    ]);
    addTearDown(container.dispose);
  });

  OrderDetailState read() => container.read(orderDetailProvider(id));
  OrderDetailController ctl() => container.read(orderDetailProvider(id).notifier);
  Future<void> boot() async {
    container.listen(orderDetailProvider(id), (_, __) {});
    await settle();
  }

  test('tải chi tiết đơn', () async {
    await boot();
    expect(read().order?.status, 'PENDING');
    expect(read().order?.canCancel, isTrue);
    expect(read().loading, isFalse);
  });

  test('lỗi tải chi tiết: hiện lỗi, thử lại được', () async {
    api.getError = http(404, 'RESOURCE_NOT_FOUND');
    await boot();
    expect(read().order, isNull);
    expect(read().error?.kind, FailureKind.notFound);
    api.getError = null;
    await ctl().reload();
    expect(read().order, isNotNull);
    expect(read().error, isNull);
  });

  test('hủy thành công: gửi đúng version, trạng thái đổi, lịch sử được tải lại', () async {
    await boot();
    api.cancelBehavior = (v) async {
      api.current = detail('CANCELLED', v + 1);
      return api.current;
    };
    expect(await ctl().cancel(), isTrue);
    expect(api.cancelVersions, [0]);
    expect(read().order?.status, 'CANCELLED');
    expect(read().order?.canCancel, isFalse);
    expect(read().cancelling, isFalse);
  });

  test('bấm hủy hai lần liên tiếp chỉ gửi một yêu cầu', () async {
    await boot();
    final gate = Completer<OrderDetail>();
    api.cancelBehavior = (_) => gate.future;
    final a = ctl().cancel();
    final b = ctl().cancel();
    expect(await b, isFalse);
    api.current = detail('CANCELLED', 1);
    gate.complete(api.current);
    expect(await a, isTrue);
    expect(api.cancelVersions.length, 1);
  });

  test('đơn đã được duyệt (409): tải lại, báo đơn đã đổi, không còn nút hủy', () async {
    await boot();
    api.current = detail('APPROVED', 1); // server đã duyệt ở phía khác
    api.cancelBehavior = (_) async => throw http(409, 'INVALID_STATE_TRANSITION');
    expect(await ctl().cancel(), isFalse);
    expect(read().order?.status, 'APPROVED');
    expect(read().order?.canCancel, isFalse);
    expect(read().notice, isNotNull);
    expect(read().actionError, isNull);
  });

  test('dữ liệu cũ (412): tải lại bản mới và báo người dùng', () async {
    await boot();
    api.current = detail('PENDING', 3);
    api.cancelBehavior = (_) async => throw http(412, 'VERSION_CONFLICT');
    expect(await ctl().cancel(), isFalse);
    expect(read().order?.version, 3);
    expect(read().notice, isNotNull);
    // Người dùng thử lại với đúng version mới.
    api.cancelBehavior = (v) async => detail('CANCELLED', v + 1);
    api.current = detail('CANCELLED', 4);
    expect(await ctl().cancel(), isTrue);
    expect(api.cancelVersions, [0, 3]);
  });

  test('offline: không báo hủy thành công, đơn giữ nguyên, hiện lỗi', () async {
    await boot();
    api.cancelBehavior = (_) async => throw ApiFailure(
        FailureKind.offline, 'Không kết nối được máy chủ.');
    expect(await ctl().cancel(), isFalse);
    expect(read().order?.status, 'PENDING');
    expect(read().actionError?.kind, FailureKind.offline);
    expect(read().cancelling, isFalse);
  });

  test('hết thời gian chờ: chưa rõ kết quả nên tải lại trạng thái thật', () async {
    await boot();
    api.current = detail('CANCELLED', 1); // thực tế server đã hủy
    api.cancelBehavior =
        (_) async => throw ApiFailure(FailureKind.timeout, 'timeout');
    expect(await ctl().cancel(), isFalse);
    expect(read().order?.status, 'CANCELLED');
    expect(read().notice, contains('Chưa rõ'));
  });

  test('đơn không còn PENDING thì không gọi API hủy', () async {
    api.current = detail('APPROVED', 1);
    await boot();
    api.cancelBehavior = (_) async => fail('không được gọi');
    expect(await ctl().cancel(), isFalse);
    expect(api.cancelVersions, isEmpty);
  });

  testWidgets('màn hình: xác nhận trước khi hủy, "Giữ đơn" không gọi API', (tester) async {
    final store = MemoryTokens()..value = const Tokens('a', 'r');
    final session = makeSession(
        store: store,
        api: FakeAdapter((_) => jsonResponse(profile())),
        auth: FakeAdapter((_) => jsonResponse({})));
    await tester.runAsync(session.restore);
    Future<void> mount() => tester.pumpWidget(ProviderScope(
        overrides: [
          sessionProvider.overrideWith((ref) => session),
          customerOrdersApiProvider.overrideWithValue(api),
          realtimeClientProvider.overrideWith((ref) => null),
        ],
        child: const MaterialApp(home: OrderDetailScreen(orderId: id))));
    await mount();
    await tester.pumpAndSettle();
    expect(find.text('Trạm Bắc'), findsOneWidget); // tên trạm, không phải UUID
    expect(find.text('2 ảnh'), findsOneWidget);
    await tester.tap(find.byKey(const Key('cancel-order')));
    await tester.pumpAndSettle();
    expect(find.text('Hủy đơn hàng?'), findsOneWidget);
    await tester.tap(find.byKey(const Key('cancel-dismiss')));
    await tester.pumpAndSettle();
    expect(api.cancelVersions, isEmpty);

    final gate = Completer<OrderDetail>();
    api.cancelBehavior = (_) => gate.future;
    await tester.tap(find.byKey(const Key('cancel-order')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('cancel-confirm')));
    await tester.pump();
    // Đang gửi: nút bị vô hiệu hóa, không bấm đúp được.
    expect(tester.widget<FilledButton>(find.byKey(const Key('cancel-order'))).onPressed,
        isNull);
    api.current = detail('CANCELLED', 1);
    gate.complete(api.current);
    await tester.pumpAndSettle();
    expect(api.cancelVersions.length, 1);
    expect(find.byKey(const Key('cancel-order')), findsNothing);
    expect(find.text('Đã hủy đơn.'), findsOneWidget);
  });

  testWidgets('màn hình: đơn đã duyệt không có nút hủy', (tester) async {
    api.current = detail('APPROVED', 1);
    final store = MemoryTokens()..value = const Tokens('a', 'r');
    final session = makeSession(
        store: store,
        api: FakeAdapter((_) => jsonResponse(profile())),
        auth: FakeAdapter((_) => jsonResponse({})));
    await tester.runAsync(session.restore);
    await tester.pumpWidget(ProviderScope(
        overrides: [
          sessionProvider.overrideWith((ref) => session),
          customerOrdersApiProvider.overrideWithValue(api),
          realtimeClientProvider.overrideWith((ref) => null),
        ],
        child: const MaterialApp(home: OrderDetailScreen(orderId: id))));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('cancel-order')), findsNothing);
    expect(find.byKey(const Key('open-tracking')), findsOneWidget);
  });
}
