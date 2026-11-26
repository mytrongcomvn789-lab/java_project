import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/core/storage/storage_service.dart';
import 'package:smartdrone_mobile/features/auth/session.dart';
import 'package:smartdrone_mobile/features/operator/operator_api.dart';
import 'package:smartdrone_mobile/features/operator/operator_controllers.dart';
import 'package:smartdrone_mobile/features/operator/operator_models.dart';
import 'package:smartdrone_mobile/features/operator/operator_screens.dart';
import 'package:smartdrone_mobile/features/orders/customer_order_models.dart';
import 'package:smartdrone_mobile/features/orders/order_detail_controller.dart';

import 'package:smartdrone_mobile/core/offline/pending_action.dart';
import 'package:smartdrone_mobile/features/operator/operator_sync.dart';

import 'support/fakes.dart';
import 'support/pending_fakes.dart';

const myStation = 'st-mine';
const otherStation = 'st-other';

OrderSummary summary(int i, {String status = 'SCHEDULED', String dropoff = otherStation}) =>
    OrderSummary(
        id: '${i.toString().padLeft(8, '0')}-0000-0000-0000-000000000000',
        status: status,
        statusLabel: status == 'SCHEDULED' ? 'Đã lên lịch' : 'Chờ nhận hàng',
        pickupStationId: myStation,
        dropoffStationId: dropoff,
        recipientName: 'Người $i',
        recipientAddressLine: 'Địa chỉ $i',
        packageDescription: 'Gói $i',
        packageWeightGrams: 100 + i);

OrderDetail detail(String id, String status, int version,
        {List<Map<String, dynamic>> history = const []}) =>
    OrderDetail.fromJson({
      'id': id,
      'version': version,
      'status': status,
      'statusLabel': status == 'SCHEDULED' ? 'Đã lên lịch' : 'Đang chuẩn bị',
      'pickupStationId': myStation,
      'dropoffStationId': otherStation,
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
        'photoFileIds': ['f1']
      },
      'history': history,
    });

DioException http(int status, String code) => DioException(
    requestOptions: RequestOptions(path: '/x'),
    response: Response(
        requestOptions: RequestOptions(path: '/x'),
        statusCode: status,
        data: {'code': code, 'message': 'msg $code'}));

/// Server giả: các đơn chờ nhận, các đơn chờ giao, và kết quả của lần nhận kiện.
class FakeOperatorApi extends OperatorApi {
  FakeOperatorApi() : super(Dio());
  int receiptTotal = 45;
  List<AssignedStation> stations = const [
    AssignedStation(id: myStation, name: 'Trạm Của Tôi', status: 'ACTIVE')
  ];
  List<OrderSummary> pickups = [];
  final receiptPages = <int>[];
  final pickupPages = <int>[];
  int receiptCalls = 0;
  Object? failNextReceipts;
  Object? failNextContext;

  // chi tiết đơn đang chờ nhận
  String orderId = '00000001-0000-0000-0000-000000000000';
  String status = 'SCHEDULED';
  int version = 2;
  final received = <ReceiveRequest>[];
  List<Map<String, dynamic>> history = [];
  Object? receiveError;
  Completer<void>? receiveGate;

  @override
  Future<List<AssignedStation>> assignedStations() async => stations;

  @override
  Future<OrderPage> listReceipts({int page = 1, int pageSize = 20, String? stationId}) async {
    receiptCalls++;
    receiptPages.add(page);
    final f = failNextReceipts;
    if (f != null) {
      failNextReceipts = null;
      throw f;
    }
    final all = [for (var i = 1; i <= receiptTotal; i++) summary(i)];
    final from = (page - 1) * pageSize;
    final items = from >= all.length
        ? <OrderSummary>[]
        : all.sublist(from, (from + pageSize).clamp(0, all.length));
    return OrderPage(items: items, page: page, pageSize: pageSize, total: receiptTotal);
  }

  @override
  Future<OrderPage> listAwaitingPickup({int page = 1, int pageSize = 20, String? stationId}) async {
    pickupPages.add(page);
    final from = (page - 1) * pageSize;
    final items = from >= pickups.length
        ? <OrderSummary>[]
        : pickups.sublist(from, (from + pageSize).clamp(0, pickups.length));
    return OrderPage(items: items, page: page, pageSize: pageSize, total: pickups.length);
  }

  @override
  Future<ReceiptContext> receiptContext(String id) async {
    final f = failNextContext;
    if (f != null) {
      failNextContext = null;
      throw f;
    }
    return ReceiptContext(
        order: detail(orderId, status, version),
        senderName: 'Khách A',
        packageId: 'pkg-12345678',
        attempt: const ReceiptAttempt(id: 'att-1', attemptNo: 1));
  }

  @override
  Future<OrderDetail> orderDetail(String id) async =>
      detail(orderId, status, version, history: history);

  @override
  Future<OrderDetail> receive(ReceiveRequest r) async {
    received.add(r);
    final g = receiveGate;
    if (g != null) await g.future;
    final e = receiveError;
    if (e != null) {
      receiveError = null;
      throw e;
    }
    status = 'PREPARING';
    version++;
    return detail(orderId, status, version);
  }
}

Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 10));

void main() {
  late FakeOperatorApi api;
  late StateProvider<String?> user;
  late ProviderContainer container;
  late MemoryPendingStore store;

  setUp(() {
    api = FakeOperatorApi();
    store = MemoryPendingStore();
    user = StateProvider<String?>((ref) => 'op-1');
    container = ProviderContainer(overrides: [
      ...pendingOverrides(store),
      operatorApiProvider.overrideWithValue(api),
      operatorUserProvider.overrideWith((ref) => ref.watch(user)),
    ]);
    addTearDown(container.dispose);
  });

  group('danh sách chờ nhận kiện', () {
    OperatorListState read() => container.read(receiptListProvider);
    Future<void> boot() async {
      container.listen(receiptListProvider, (_, __) {});
      await settle();
    }

    test('phân trang tới hết, không trùng đơn', () async {
      await boot();
      expect(read().items.length, 20);
      final c = container.read(receiptListProvider.notifier);
      await c.loadMore();
      await c.loadMore();
      expect(read().items.length, 45);
      expect(read().items.map((e) => e.id).toSet().length, 45);
      expect(read().hasMore, isFalse);
      expect(api.receiptPages, [1, 2, 3]);
    });

    test('lỗi trang đầu hiện lỗi, thử lại được; lỗi làm mới giữ danh sách', () async {
      api.failNextReceipts = DioException.connectionError(
          requestOptions: RequestOptions(), reason: 'off');
      await boot();
      expect(read().error?.kind, FailureKind.offline);
      await container.read(receiptListProvider.notifier).reload();
      expect(read().items.length, 20);
      api.failNextReceipts = http(500, 'INTERNAL_ERROR');
      await container.read(receiptListProvider.notifier).reload();
      expect(read().items.length, 20);
      expect(read().error, isNull);
      expect(read().moreError, isNotNull);
    });

    test('đổi tài khoản bỏ danh sách của người trước', () async {
      await boot();
      expect(read().items, isNotEmpty);
      api.receiptTotal = 2;
      container.read(user.notifier).state = 'op-2';
      await settle();
      expect(read().items.length, 2);
    });
  });

  group('danh sách chờ giao khách', () {
    OperatorListState read() => container.read(pickupListProvider);
    Future<void> boot() async {
      container.listen(pickupListProvider, (_, __) {});
      await settle();
    }

    test('chỉ giữ đơn có trạm nhận thuộc trạm được phân công', () async {
      api.pickups = [
        summary(1, status: 'AWAITING_PICKUP', dropoff: myStation),
        summary(2, status: 'AWAITING_PICKUP', dropoff: otherStation), // chỉ trạm gửi là của tôi
        summary(3, status: 'AWAITING_PICKUP', dropoff: myStation),
      ];
      await boot();
      expect(read().items.map((e) => e.packageDescription), ['Gói 1', 'Gói 3']);
      expect(read().total, 2);
    });

    test('đọc hết các trang rồi mới lọc', () async {
      api.pickups = [
        for (var i = 1; i <= 60; i++)
          summary(i, status: 'AWAITING_PICKUP', dropoff: i.isEven ? myStation : otherStation)
      ];
      await boot();
      expect(api.pickupPages, [1, 2]);
      expect(read().items.length, 30);
    });

    test('chưa được phân công trạm nào thì danh sách rỗng', () async {
      api.stations = const [];
      api.pickups = [summary(1, status: 'AWAITING_PICKUP', dropoff: myStation)];
      await boot();
      expect(read().items, isEmpty);
      expect(read().error, isNull);
    });
  });

  group('chi tiết nhận kiện', () {
    ReceiptDetailState read() => container.read(receiptDetailProvider(api.orderId));
    ReceiptDetailController ctl() =>
        container.read(receiptDetailProvider(api.orderId).notifier);
    Future<void> boot() async {
      container.listen(receiptDetailProvider(api.orderId), (_, __) {});
      await settle();
    }

    test('tải chi tiết và cho nhận khi đơn đang SCHEDULED', () async {
      await boot();
      expect(read().context?.senderName, 'Khách A');
      expect(read().canReceive, isTrue);
    });

    test('nhận kiện: gửi đúng phiên bản, attempt, kiện và ghi chú; làm mới danh sách', () async {
      container.listen(receiptListProvider, (_, __) {});
      await boot();
      final callsBefore = api.receiptCalls;
      expect(await ctl().receive('  Nguyên vẹn '), isTrue);
      final r = api.received.single;
      expect(r.version, 2);
      expect(r.attemptId, 'att-1');
      expect(r.packageId, 'pkg-12345678');
      expect(r.toBody()['note'], 'Nguyên vẹn');
      await settle();
      expect(api.receiptCalls, greaterThan(callsBefore)); // danh sách được tải lại
      expect(read().receiving, isFalse);
    });

    test('bấm đúp: chỉ gửi một yêu cầu', () async {
      await boot();
      api.receiveGate = Completer<void>();
      final a = ctl().receive(null);
      final b = ctl().receive(null);
      expect(await b, isFalse);
      api.receiveGate!.complete();
      expect(await a, isTrue);
      expect(api.received.length, 1);
    });

    test('phiên bản cũ (412): tải lại bản mới, báo người dùng', () async {
      await boot();
      api.version = 5; // đơn đã đổi phía server
      api.receiveError = http(412, 'VERSION_CONFLICT');
      expect(await ctl().receive(null), isFalse);
      expect(read().context?.order.version, 5);
      expect(read().notice, isNotNull);
      expect(read().canReceive, isTrue); // vẫn SCHEDULED: thử lại với phiên bản mới
      api.receiveError = null;
      expect(await ctl().receive(null), isTrue);
      expect(api.received.map((e) => e.version), [2, 5]);
    });

    test('đơn đã được nhận (409): tải lại và không cho nhận nữa', () async {
      await boot();
      api.status = 'PREPARING';
      api.version = 3;
      api.receiveError = http(409, 'INVALID_STATE_TRANSITION');
      expect(await ctl().receive(null), isFalse);
      expect(read().order?.status, 'PREPARING');
      expect(read().canReceive, isFalse);
      expect(read().notice, isNotNull);
    });

    test('sai trạm hoặc ngoài phạm vi (404): báo và không thao tác tiếp', () async {
      await boot();
      api.receiveError = http(404, 'RESOURCE_NOT_FOUND');
      expect(await ctl().receive(null), isFalse);
      expect(read().gone, isTrue);
      expect(read().canReceive, isFalse);
      expect(read().actionError?.kind, FailureKind.notFound);
    });

    test('mất mạng: không báo thành công, xếp vào hàng đợi, không nhận lần hai', () async {
      await boot();
      api.receiveError = DioException.connectionError(
          requestOptions: RequestOptions(), reason: 'off');
      expect(await ctl().receive('ghi chú'), isFalse);
      expect(read().queued, isTrue);
      expect(read().actionError, isNull);
      expect(read().canReceive, isFalse);
      expect(read().receiving, isFalse);
      final saved = store.data['op-1']!.single;
      expect(saved.kind, PendingKind.receive);
      expect(saved.payload['version'], 2);
      expect(await ctl().receive(null), isFalse); // đã xếp hàng: không gửi thêm
      expect(api.received.length, 1);
    });

    test('mất mạng nhưng không lưu được hàng đợi: báo lỗi và vẫn nhận lại được', () async {
      await boot();
      store.failWrites = true;
      api.receiveError = DioException.connectionError(
          requestOptions: RequestOptions(), reason: 'off');
      expect(await ctl().receive(null), isFalse);
      expect(read().queued, isFalse);
      expect(read().actionError?.kind, FailureKind.offline);
      expect(read().canReceive, isTrue);
    });

    test('hết thời gian chờ: chưa rõ kết quả nên xếp hàng, đồng bộ sẽ kiểm tra đơn trước', () async {
      await boot();
      api.receiveError = DioException(
          requestOptions: RequestOptions(), type: DioExceptionType.receiveTimeout);
      expect(await ctl().receive(null), isFalse);
      expect(read().queued, isTrue);
      // Thực tế lần gửi trước đã tới server: đơn đã PREPARING và lịch sử có bước đó.
      api.status = 'PREPARING';
      api.version = 3;
      api.history = [
        {'eventId': 'e1', 'orderVersion': 3, 'fromStatus': 'SCHEDULED', 'toStatus': 'PREPARING'}
      ];
      await container.read(operatorSyncProvider.notifier).syncNow();
      expect(api.received.length, 1); // không gửi lại lần hai
      expect(container.read(operatorSyncProvider).isEmpty, isTrue);
    });

    test('đơn không còn SCHEDULED thì không gọi API nhận', () async {
      api.status = 'PREPARING';
      await boot();
      expect(await ctl().receive(null), isFalse);
      expect(api.received, isEmpty);
    });

    test('lỗi tải chi tiết: 404 coi như ngoài phạm vi, lỗi khác thử lại được', () async {
      api.failNextContext = http(404, 'RESOURCE_NOT_FOUND');
      await boot();
      expect(read().gone, isTrue);
      expect(read().context, isNull);
    });
  });

  group('màn hình', () {
    late Session session;

    Future<void> mount(WidgetTester tester, Widget home) async {
      final tokens = MemoryTokens()..value = const Tokens('a', 'r');
      session = makeSession(
          store: tokens,
          api: FakeAdapter((_) => jsonResponse(profile(role: 'STATION_OPERATOR', name: 'Vận Hành'))),
          auth: FakeAdapter((_) => jsonResponse({})));
      await tester.runAsync(session.restore);
      final router = GoRouter(routes: [
        GoRoute(path: '/', builder: (_, __) => home),
        GoRoute(path: '/operator', builder: (_, __) => const Scaffold(body: Text('OPERATOR_HOME'))),
        GoRoute(
            path: '/operator/receipts/:id',
            builder: (_, state) => ReceiptDetailScreen(orderId: state.pathParameters['id']!)),
      ]);
      await tester.pumpWidget(ProviderScope(
          overrides: [
            ...pendingOverrides(store),
            sessionProvider.overrideWith((ref) => session),
            operatorApiProvider.overrideWithValue(api),
            stationsProvider.overrideWith((ref) async => const [
                  Station(id: myStation, name: 'Trạm Của Tôi', latitude: 0, longitude: 0, status: 'ACTIVE'),
                  Station(id: otherStation, name: 'Trạm Khác', latitude: 0, longitude: 0, status: 'ACTIVE'),
                ]),
          ],
          child: MaterialApp.router(routerConfig: router)));
      await tester.pumpAndSettle();
    }

    testWidgets('màn chính: trạm của tôi, hai tab có số đơn, mở đơn chờ nhận', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      api.receiptTotal = 3;
      api.pickups = [summary(9, status: 'AWAITING_PICKUP', dropoff: myStation)];
      await mount(tester, const OperatorHomeScreen());
      expect(find.descendant(of: find.byKey(const Key('station-selector')), matching: find.text('Trạm Của Tôi')), findsOneWidget);
      expect(find.text('Chờ nhận (3)'), findsOneWidget);
      expect(find.text('Chờ giao (1)'), findsOneWidget);
      expect(find.textContaining('Trạm Của Tôi → Trạm Khác'), findsWidgets);
      await tester.tap(find.byKey(Key('order-${summary(1).id}')));
      await tester.pumpAndSettle();
      expect(find.text('Đơn ${summary(1).shortCode}'), findsOneWidget);
      expect(find.byKey(const Key('receive-button')), findsOneWidget);
    });

    testWidgets('chưa được phân công trạm: hiện cảnh báo', (tester) async {
      api.stations = const [];
      api.receiptTotal = 0;
      await mount(tester, const OperatorHomeScreen());
      expect(find.textContaining('chưa được phân công trạm nào'), findsOneWidget);
      expect(find.byKey(const Key('list-empty')), findsOneWidget);
    });

    testWidgets('tab chờ giao hiện đơn nhưng chưa bấm xác nhận được', (tester) async {
      api.receiptTotal = 0;
      api.pickups = [summary(9, status: 'AWAITING_PICKUP', dropoff: myStation)];
      await mount(tester, const OperatorHomeScreen());
      await tester.tap(find.byKey(const Key('tab-pickups')));
      await tester.pumpAndSettle();
      expect(find.text('Đơn ${summary(9).shortCode}'), findsOneWidget);
    });

    testWidgets('nhận kiện: phải tích kiểm tra, có hộp thoại xác nhận, bấm đúp chỉ gửi một lần',
        (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await mount(tester, ReceiptDetailScreen(orderId: api.orderId));
      expect(find.text('Trạm Của Tôi'), findsOneWidget); // tên trạm, không phải mã
      FilledButton button() =>
          tester.widget<FilledButton>(find.byKey(const Key('receive-button')));
      expect(button().onPressed, isNull); // chưa tích kiểm tra kiện
      await tester.tap(find.byKey(const Key('receipt-checked')));
      await tester.pump();
      expect(button().onPressed, isNotNull);

      await tester.tap(find.byKey(const Key('receive-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('receive-dismiss')));
      await tester.pumpAndSettle();
      expect(api.received, isEmpty); // "Quay lại" không gọi API

      await tester.enterText(find.byKey(const Key('receipt-note')), 'Bao bì tốt');
      api.receiveGate = Completer<void>();
      await tester.tap(find.byKey(const Key('receive-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('receive-confirm')));
      await tester.pump();
      expect(button().onPressed, isNull); // đang gửi: không bấm đúp được
      api.receiveGate!.complete();
      await tester.pumpAndSettle();
      expect(api.received.length, 1);
      expect(api.received.single.note, 'Bao bì tốt');
    });

    testWidgets('mất mạng khi nhận: báo đã lưu, đơn CHƯA được nhận, không báo thành công', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await mount(tester, ReceiptDetailScreen(orderId: api.orderId));
      await tester.tap(find.byKey(const Key('receipt-checked')));
      await tester.pump();
      api.receiveError = DioException.connectionError(
          requestOptions: RequestOptions(), reason: 'off');
      await tester.tap(find.byKey(const Key('receive-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('receive-confirm')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('receipt-queued')), findsOneWidget);
      expect(find.textContaining('CHƯA được nhận'), findsOneWidget);
      expect(find.text('Đã nhận kiện.'), findsNothing);
      expect(find.byKey(const Key('receive-button')), findsNothing);
      // Tài khoản trong test màn hình là "a" (do phiên đăng nhập giả cấp).
      expect(store.data['a']!.single.kind, PendingKind.receive);
    });

    testWidgets('đơn đã được nhận: không còn nút nhận', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      api.status = 'PREPARING';
      await mount(tester, ReceiptDetailScreen(orderId: api.orderId));
      expect(find.byKey(const Key('receive-button')), findsNothing);
      expect(find.byKey(const Key('receipt-not-pending')), findsOneWidget);
    });
  });
}
