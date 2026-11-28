import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_provider.dart';
import 'package:smartdrone_mobile/core/storage/storage_service.dart';
import 'package:smartdrone_mobile/core/theme/app_theme.dart';
import 'package:smartdrone_mobile/core/ui/demo_badge.dart';
import 'package:smartdrone_mobile/features/ai/ai_api.dart';
import 'package:smartdrone_mobile/features/ai/ai_models.dart';
import 'package:smartdrone_mobile/features/auth/session.dart';
import 'package:smartdrone_mobile/features/flight/flight_api.dart';
import 'package:smartdrone_mobile/features/orders/customer_order_models.dart' show OrderDetail;
import 'package:smartdrone_mobile/features/orders/customer_orders_api.dart';
import 'package:smartdrone_mobile/features/orders/order_models.dart';
import 'package:smartdrone_mobile/features/orders/orders_api.dart';
import 'package:smartdrone_mobile/features/orders/pickup_code.dart';
import 'package:smartdrone_mobile/features/orders/tracking_screen.dart';

import 'support/fakes.dart';

const oid = '11111111-1111-1111-1111-111111111111';

class FakeOrders extends OrdersApi {
  FakeOrders(this.snapshot) : super(Dio());
  TrackingSnapshot snapshot;
  @override
  Future<TrackingSnapshot> tracking(String orderId) async => snapshot;
}

class FakeCustomerApi extends CustomerOrdersApi {
  FakeCustomerApi() : super(Dio());
  final cancelled = <(String, int)>[];
  ApiFailure? failure;
  @override
  Future<OrderDetail> cancelOrder(String id, int version) async {
    cancelled.add((id, version));
    if (failure != null) throw failure!;
    return OrderDetail.fromJson({
      'id': id,
      'version': version + 1,
      'status': 'CANCELLED',
      'statusLabel': 'Đã hủy',
      'pickupStationId': 's1',
      'dropoffStationId': 's2',
      'recipient': {'name': 'An', 'phone': '0901234567', 'addressLine': 'x'},
      'package': {
        'description': 'g',
        'weightGrams': 1,
        'lengthCm': 1,
        'widthCm': 1,
        'heightCm': 1,
        'photoFileIds': <String>[]
      },
      'history': <dynamic>[],
    });
  }
}

class FakeAi extends AiApi {
  FakeAi() : super(Dio());
  @override
  Future<AiEnvelope<EtaResult>> eta(String orderId, {CancelToken? cancel}) async =>
      AiEnvelope(
          result: const EtaResult(
              orderId: oid, status: 'X', applicable: true, computed: true, etaMinutes: 14),
          model: null,
          mock: false,
          degraded: false,
          source: AiSource.formula,
          requestId: 'r',
          generatedAt: null,
          fallbackReason: null);
}

class FakeFlight extends FlightApi {
  FakeFlight({required this.demo}) : super(Dio());
  final bool demo;
  @override
  Future<Sourced<Flight>> flight(String orderId) async => Sourced(
      const Flight(
          progress: 0.5,
          telemetry: FlightTelemetry(speedKmh: 52, altitudeM: 120, batteryPercent: 78)),
      isDemo: demo);
}

TrackingSnapshot snap(String status,
        {int version = 3,
        List<String> actions = const [],
        OrderPosition? position,
        StationPoint? pickup = const StationPoint('Trạm Gửi', latitude: 10.0, longitude: 106.0),
        StationPoint? dropoff = const StationPoint('Trạm Nhận', latitude: 10.05, longitude: 106.1)}) =>
    TrackingSnapshot(
        orderId: oid,
        version: version,
        status: status,
        statusLabel: 'Nhãn $status',
        terminal: false,
        pickup: pickup,
        dropoff: dropoff,
        position: position,
        allowedActions: actions,
        timeline: [
          for (var i = 1; i <= version; i++)
            TimelineEntry(eventId: 'e$i', version: i, toStatus: status, label: 'Bước $i')
        ]);

void main() {
  late FakeCustomerApi customer;

  Future<void> mount(WidgetTester tester, TrackingSnapshot s,
      {bool demo = true, PickupCode? code, ApiFailure? codeError}) async {
    tester.view.physicalSize = const Size(1170, 2532);
    tester.view.devicePixelRatio = 3;
    addTearDown(tester.view.reset);
    customer = FakeCustomerApi();
    final session = makeSession(
        store: MemoryTokens()..value = const Tokens('a', 'r'),
        api: FakeAdapter((_) => jsonResponse(profile())),
        auth: FakeAdapter((_) => jsonResponse({})));
    await tester.runAsync(session.restore);
    final router = GoRouter(routes: [
      GoRoute(path: '/', builder: (_, __) => const OrderTrackingScreen(orderId: oid)),
      GoRoute(path: '/customer', builder: (_, __) => const Scaffold(body: Text('HOME'))),
      GoRoute(
          path: '/customer/orders/:id/detail',
          builder: (_, __) => const Scaffold(body: Text('DETAIL'))),
    ]);
    await tester.pumpWidget(ProviderScope(
        overrides: [
          sessionProvider.overrideWith((ref) => session),
          realtimeClientProvider.overrideWith((ref) => null),
          ordersApiProvider.overrideWithValue(FakeOrders(s)),
          customerOrdersApiProvider.overrideWithValue(customer),
          aiApiProvider.overrideWithValue(FakeAi()),
          flightApiProvider.overrideWithValue(FakeFlight(demo: demo)),
          pickupCodeProvider.overrideWith((ref, id) async {
            if (codeError != null) throw codeError;
            return code ?? PickupCode(orderId: id, otp: '482913', qrPayload: 'qr', expiresAt: DateTime(2030, 1, 1, 14, 5));
          }),
        ],
        child: MaterialApp.router(theme: buildAppTheme(), routerConfig: router)));
    await tester.pumpAndSettle();
  }

  group('RouteMap.project', () {
    const size = Size(360, 230);
    test('hai trạm khác nhau: nằm trong khung và theo đúng hướng', () {
      final (a, b) = RouteMap.project(
          const StationPoint('A', latitude: 10.0, longitude: 106.0),
          const StationPoint('B', latitude: 10.05, longitude: 106.1),
          size);
      for (final p in [a, b]) {
        expect(p.dx, inInclusiveRange(0, size.width));
        expect(p.dy, inInclusiveRange(0, size.height));
      }
      expect(b.dx, greaterThan(a.dx)); // B ở phía đông
      expect(b.dy, lessThan(a.dy)); // B ở phía bắc (y màn hình hướng xuống)
    });

    test('hai trạm trùng toạ độ vẫn tách ra để đọc được', () {
      final (a, b) = RouteMap.project(
          const StationPoint('A', latitude: 10.0, longitude: 106.0),
          const StationPoint('B', latitude: 10.0, longitude: 106.0),
          size);
      expect((a - b).distance, greaterThan(48));
    });

    test('tiến độ minh hoạ theo giai đoạn', () {
      expect(RouteMap.progressOf('AT_PICKUP'), 0);
      expect(RouteMap.progressOf('IN_FLIGHT'), 0.5);
      expect(RouteMap.progressOf('AT_DROPOFF'), 1);
      expect(RouteMap.progressOf('UNKNOWN'), isNull);
      expect(RouteMap.progressOf(null), isNull);
    });
  });

  test('TrackingSnapshot đọc toạ độ, vị trí suy ra và thao tác được phép', () {
    final s = TrackingSnapshot.fromJson({
      'orderId': oid,
      'version': 4,
      'status': 'IN_TRANSIT',
      'terminal': false,
      'pickup': {'name': 'A', 'latitude': 10.5, 'longitude': 106.5},
      'dropoff': {'name': 'B', 'latitude': null, 'longitude': null},
      'map': {'mode': 'STATIONS_ONLY', 'liveGpsAvailable': false},
      'position': {'phase': 'IN_FLIGHT', 'simulated': true},
      'allowedActions': ['CANCEL'],
      'timeline': <dynamic>[],
    });
    expect(s.pickup!.hasCoordinates, isTrue);
    expect(s.dropoff!.hasCoordinates, isFalse);
    expect(s.position!.phase, 'IN_FLIGHT');
    expect(s.position!.simulated, isTrue);
    expect(s.liveGpsAvailable, isFalse);
    expect(s.allowedActions, ['CANCEL']);
  });

  testWidgets('đang bay: bản đồ có drone ghi rõ "mô phỏng", số đo Demo, ETA thật', (tester) async {
    await mount(tester, snap('IN_TRANSIT', position: const OrderPosition('IN_FLIGHT', simulated: true)));
    expect(find.byKey(const Key('route-map')), findsOneWidget);
    expect(find.byKey(const Key('map-drone')), findsOneWidget);
    expect(find.byKey(const Key('map-simulated')), findsOneWidget);
    expect(find.text('Trạm Gửi'), findsOneWidget);
    expect(find.text('Trạm Nhận'), findsOneWidget);
    expect(find.byKey(const Key('telemetry')), findsOneWidget);
    expect(find.text('52 km/h'), findsOneWidget);
    expect(find.byType(DemoBadge), findsOneWidget);
    expect(find.textContaining('14'), findsWidgets); // ETA 14 phút từ API thật
  });

  testWidgets('số đo thật thì không hiện nhãn Demo', (tester) async {
    await mount(tester, snap('IN_TRANSIT', position: const OrderPosition('IN_FLIGHT', simulated: true)),
        demo: false);
    expect(find.byKey(const Key('telemetry')), findsOneWidget);
    expect(find.byType(DemoBadge), findsNothing);
  });

  testWidgets('chưa bay: không có khung số đo và không vẽ nhãn mô phỏng', (tester) async {
    await mount(tester, snap('SCHEDULED', position: const OrderPosition('AT_PICKUP')));
    expect(find.byKey(const Key('telemetry')), findsNothing);
    expect(find.byKey(const Key('map-simulated')), findsNothing);
    expect(find.byKey(const Key('map-drone')), findsOneWidget); // drone đứng ở trạm gửi
  });

  testWidgets('thiếu toạ độ trạm: báo chưa có toạ độ, không vẽ drone', (tester) async {
    await mount(tester,
        snap('IN_TRANSIT',
            position: const OrderPosition('IN_FLIGHT', simulated: true),
            pickup: const StationPoint('Trạm Gửi')));
    expect(find.byKey(const Key('map-no-coordinates')), findsOneWidget);
    expect(find.byKey(const Key('map-drone')), findsNothing);
  });

  testWidgets('chờ nhận hàng: hiện mã OTP cách nhóm và hạn dùng', (tester) async {
    await mount(tester, snap('AWAITING_PICKUP', position: const OrderPosition('AT_DROPOFF')));
    expect(find.byKey(const Key('pickup-code-card')), findsOneWidget);
    expect(find.text('482 913'), findsOneWidget);
    expect(find.textContaining('14:05'), findsOneWidget);
  });

  testWidgets('lỗi lấy mã nhận hàng: báo lỗi và cho thử lại', (tester) async {
    await mount(tester, snap('AWAITING_PICKUP'),
        codeError: const ApiFailure(FailureKind.pickupCodeExpired, 'Mã đã hết hạn'));
    expect(find.byKey(const Key('pickup-code-error')), findsOneWidget);
    expect(find.byKey(const Key('pickup-code-retry')), findsOneWidget);
  });

  testWidgets('trạng thái khác không hiện mã nhận hàng', (tester) async {
    await mount(tester, snap('IN_TRANSIT'));
    expect(find.byKey(const Key('pickup-code-card')), findsNothing);
  });

  testWidgets('nút "Hỗ trợ" luôn bị tắt vì backend chưa có kênh hỗ trợ', (tester) async {
    await mount(tester, snap('IN_TRANSIT'));
    final button = tester.widget<OutlinedButton>(find.byKey(const Key('contact-support')));
    expect(button.onPressed, isNull);
  });

  testWidgets('chỉ hiện "Hủy đơn" khi server cho phép; xác nhận rồi gửi kèm phiên bản đang xem',
      (tester) async {
    await mount(tester, snap('PENDING', version: 1, actions: ['CANCEL']));
    expect(find.byKey(const Key('cancel-order')), findsOneWidget);
    await tester.tap(find.byKey(const Key('cancel-order')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('cancel-dismiss')));
    await tester.pumpAndSettle();
    expect(customer.cancelled, isEmpty, reason: 'Giữ đơn thì không gọi API');
    await tester.tap(find.byKey(const Key('cancel-order')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('cancel-confirm')));
    await tester.pumpAndSettle();
    expect(customer.cancelled, [(oid, 1)]);
  });

  testWidgets('không có quyền hủy thì không có nút hủy', (tester) async {
    await mount(tester, snap('IN_TRANSIT', actions: const []));
    expect(find.byKey(const Key('cancel-order')), findsNothing);
  });

  testWidgets('hủy bị server từ chối: hiện thông báo lỗi của server', (tester) async {
    await mount(tester, snap('PENDING', version: 1, actions: ['CANCEL']));
    customer.failure = const ApiFailure(FailureKind.stateConflict, 'Đơn đã được duyệt');
    await tester.tap(find.byKey(const Key('cancel-order')));
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('cancel-confirm')));
    await tester.pumpAndSettle();
    expect(find.text('Đơn đã được duyệt'), findsOneWidget);
  });

  testWidgets('timeline hiện mốc mới nhất đầu tiên', (tester) async {
    await mount(tester, snap('IN_TRANSIT', version: 3));
    await tester.scrollUntilVisible(find.byKey(const Key('timeline-v1')), 300,
        scrollable: find.byType(Scrollable).first);
    final latest = tester.getTopLeft(find.byKey(const Key('timeline-v3'))).dy;
    final first = tester.getTopLeft(find.byKey(const Key('timeline-v1'))).dy;
    expect(latest, lessThan(first));
  });
}
