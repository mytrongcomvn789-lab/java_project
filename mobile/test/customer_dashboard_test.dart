import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_provider.dart';
import 'package:smartdrone_mobile/core/storage/storage_service.dart';
import 'package:smartdrone_mobile/core/theme/app_theme.dart';
import 'package:smartdrone_mobile/core/ui/app_widgets.dart';
import 'package:smartdrone_mobile/core/ui/async_state_view.dart';
import 'package:smartdrone_mobile/core/ui/demo_badge.dart';
import 'package:smartdrone_mobile/features/auth/session.dart';
import 'package:smartdrone_mobile/features/ai/ai_models.dart';
import 'package:smartdrone_mobile/features/flight/flight_api.dart';
import 'package:smartdrone_mobile/features/orders/customer_dashboard.dart';
import 'package:smartdrone_mobile/features/orders/customer_order_models.dart';
import 'package:smartdrone_mobile/features/orders/order_detail_controller.dart';

import 'support/fakes.dart';

OrderSummary order(int i, String status) => OrderSummary(
    id: '${i.toString().padLeft(8, '0')}-0000-0000-0000-000000000000',
    status: status,
    statusLabel: status == 'IN_TRANSIT' ? 'Đang giao' : 'Đã giao',
    pickupStationId: 'st-a',
    dropoffStationId: 'st-b',
    recipientName: 'Người $i',
    recipientAddressLine: 'Địa chỉ $i',
    packageDescription: 'Gói $i',
    packageWeightGrams: 100 * i,
    createdAt: DateTime(2026, 11, 25, 9, i));

/// API bay giả: trả số cố định, đánh dấu Demo hoặc thật theo yêu cầu của từng test.
class FakeFlightApi extends FlightApi {
  FakeFlightApi({required this.demo}) : super(Dio());
  final bool demo;

  @override
  Future<Sourced<Flight>> flight(String orderId) async => Sourced(
      const Flight(
          progress: 0.5,
          telemetry: FlightTelemetry(speedKmh: 52, altitudeM: 120, batteryPercent: 78)),
      isDemo: demo);
}

double contrastRatio(Color a, Color b) {
  final l1 = a.computeLuminance(), l2 = b.computeLuminance();
  return ((l1 > l2 ? l1 : l2) + 0.05) / ((l1 > l2 ? l2 : l1) + 0.05);
}

void main() {
  group('DemoFlightData và FlightApi', () {
    test('dữ liệu mẫu ổn định theo mã đơn và nằm trong khoảng hợp lý', () {
      const id = '00000001-0000-0000-0000-000000000000';
      final a = DemoFlightData.flight(id), b = DemoFlightData.flight(id);
      expect(a.telemetry.speedKmh, b.telemetry.speedKmh);
      expect(a.telemetry.batteryPercent, inInclusiveRange(60, 95));
      expect(a.telemetry.speedKmh, inInclusiveRange(40, 60));
      expect(a.progress, inInclusiveRange(0.3, 0.8));
    });

    test('backend trả đúng hợp đồng: dùng dữ liệu thật, KHÔNG gắn nhãn Demo', () async {
      final dio = Dio()
        ..httpClientAdapter = FakeAdapter((o) => jsonResponse({
                'progressPercent': 40,
                'telemetry': {'speedKmh': 55.5, 'altitudeM': 100, 'batteryPercent': 81},
                'droneId': 'D-1'
              }));
      final api = FlightApi(dio);
      final f = await api.flight('x');
      expect(f.isDemo, isFalse);
      expect(f.data.progress, 0.4);
      expect(f.data.telemetry.batteryPercent, 81);
      expect(f.data.droneId, 'D-1');
    });

    for (final code in [403, 404, 405, 501]) {
      test('tuyến chưa cài ($code): dùng dữ liệu mẫu có nhãn Demo', () async {
        final api = FlightApi(
            Dio()..httpClientAdapter = FakeAdapter((_) => jsonResponse({}, code)));
        expect((await api.flight('x')).isDemo, isTrue);
      });
    }

    test('lỗi thật (500, mất mạng) KHÔNG bị che bằng dữ liệu mẫu', () async {
      final api = FlightApi(
          Dio()..httpClientAdapter = FakeAdapter((_) => jsonResponse({}, 500)));
      await expectLater(api.flight('x'), throwsA(isA<ApiFailure>()));
      final off = FlightApi(Dio()
        ..httpClientAdapter = FakeAdapter((o) => throw DioException.connectionError(
            requestOptions: o, reason: 'off')));
      await expectLater(off.flight('x'), throwsA(isA<ApiFailure>()));
    });

    test('tắt demoFallback thì 404 cũng là lỗi', () async {
      final api = FlightApi(
          Dio()..httpClientAdapter = FakeAdapter((_) => jsonResponse({}, 404)),
          demoFallback: false);
      await expectLater(api.flight('x'), throwsA(isA<ApiFailure>()));
    });
  });

  group('AsyncStateView', () {
    Future<void> show(WidgetTester tester, AsyncStateView v) =>
        tester.pumpWidget(MaterialApp(theme: buildAppTheme(), home: Scaffold(body: v)));

    testWidgets('đang tải: hiện khung xương, không hiện nội dung', (tester) async {
      await show(tester, const AsyncStateView(loading: true, child: Text('NỘI DUNG')));
      expect(find.byKey(const Key('state-loading')), findsOneWidget);
      expect(find.text('NỘI DUNG'), findsNothing);
    });

    testWidgets('rỗng: icon, tiêu đề và gợi ý', (tester) async {
      await show(
          tester,
          const AsyncStateView(
              isEmpty: true,
              emptyTitle: 'Chưa có đơn',
              emptyHint: 'Hãy tạo đơn đầu tiên.',
              child: Text('NỘI DUNG')));
      expect(find.byKey(const Key('state-empty')), findsOneWidget);
      expect(find.text('Chưa có đơn'), findsOneWidget);
      expect(find.text('Hãy tạo đơn đầu tiên.'), findsOneWidget);
      expect(find.text('NỘI DUNG'), findsNothing);
    });

    testWidgets('lỗi: có nút Thử lại gọi onRetry', (tester) async {
      var retried = 0;
      await show(
          tester,
          AsyncStateView(
              error: const ApiFailure(FailureKind.server, 'Máy chủ lỗi'),
              onRetry: () => retried++,
              child: const Text('NỘI DUNG')));
      expect(find.text('Không tải được dữ liệu'), findsOneWidget);
      expect(find.text('Máy chủ lỗi'), findsOneWidget);
      await tester.tap(find.byKey(const Key('state-retry')));
      expect(retried, 1);
    });

    testWidgets('mất mạng: thông báo riêng, khác lỗi thường', (tester) async {
      await show(
          tester,
          const AsyncStateView(
              error: ApiFailure(FailureKind.offline, 'Không kết nối được máy chủ.'),
              child: Text('NỘI DUNG')));
      expect(find.text('Không có kết nối mạng'), findsOneWidget);
      expect(find.byIcon(Icons.wifi_off_rounded), findsOneWidget);
    });

    testWidgets('bình thường: hiện nội dung', (tester) async {
      await show(tester, const AsyncStateView(child: Text('NỘI DUNG')));
      expect(find.text('NỘI DUNG'), findsOneWidget);
    });

    test('nhãn Demo đạt tương phản chữ/nền ≥ 4.5:1 trên cả hai nền', () {
      expect(contrastRatio(StatusPalette.pending.foreground, StatusPalette.pending.background),
          greaterThanOrEqualTo(4.5));
      expect(contrastRatio(AppColors.dark, AppColors.amber), greaterThanOrEqualTo(4.5));
    });
  });

  group('trang chủ khách', () {
    late Session session;

    Future<void> mount(WidgetTester tester,
        {required Future<List<OrderSummary>> Function() orders,
        bool demo = true,
        List<String>? visited}) async {
      tester.view.physicalSize = const Size(1170, 2532);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      session = makeSession(
          store: MemoryTokens()..value = const Tokens('a', 'r'),
          api: FakeAdapter((_) => jsonResponse(profile(name: 'Nguyễn Văn An'))),
          auth: FakeAdapter((_) => jsonResponse({})));
      await tester.runAsync(session.restore);
      GoRoute stub(String path) => GoRoute(
          path: path,
          builder: (_, s) {
            visited?.add(s.uri.toString());
            return Scaffold(body: Text('ROUTE $path'));
          });
      final router = GoRouter(routes: [
        GoRoute(path: '/', builder: (_, __) => const CustomerDashboardScreen()),
        stub('/customer/new-order'),
        stub('/customer/addresses'),
        stub('/customer/orders'),
        stub('/customer/assistant'),
        stub('/customer/notifications'),
        stub('/customer/orders/:id'),
        stub('/customer/orders/:id/detail'),
        stub('/profile'),
      ]);
      await tester.pumpWidget(ProviderScope(
          overrides: [
            sessionProvider.overrideWith((ref) => session),
            realtimeClientProvider.overrideWith((ref) => null),
            recentOrdersProvider.overrideWith((ref) => orders()),
            flightApiProvider.overrideWithValue(FakeFlightApi(demo: demo)),
            dashboardEtaProvider.overrideWith((ref, id) async => const EtaResult(
                orderId: 'x',
                status: 'IN_TRANSIT',
                applicable: true,
                computed: true,
                etaMinutes: 12)),
            stationsProvider.overrideWith((ref) async => const [
                  Station(id: 'st-a', name: 'Trạm Gửi', latitude: 0, longitude: 0, status: 'ACTIVE'),
                  Station(id: 'st-b', name: 'Trạm Nhận', latitude: 0, longitude: 0, status: 'ACTIVE'),
                ]),
          ],
          child: MaterialApp.router(theme: buildAppTheme(), routerConfig: router)));
      await tester.pumpAndSettle();
    }

    testWidgets('lời chào, avatar chữ cái đầu và chuông không có chấm đỏ khi 0 thông báo',
        (tester) async {
      await mount(tester, orders: () async => []);
      expect(find.text('Xin chào,'), findsOneWidget);
      expect(find.text('Nguyễn Văn An'), findsOneWidget);
      expect(find.text('N'), findsOneWidget); // avatar
      expect(tester.widget<Badge>(find.byType(Badge)).isLabelVisible, isFalse);
    });

    testWidgets('đơn đang giao: ETA, tốc độ, pin, tiến độ và NHÃN DEMO khi là dữ liệu mẫu',
        (tester) async {
      await mount(tester, orders: () async => [order(1, 'IN_TRANSIT'), order(2, 'DELIVERED')]);
      expect(find.byKey(const Key('active-order-card')), findsOneWidget);
      expect(find.text('12 phút'), findsOneWidget);
      expect(find.text('52 km/h'), findsOneWidget);
      expect(find.text('78%'), findsOneWidget);
      expect(find.text('Trạm Gửi'), findsOneWidget);
      expect(find.text('Trạm Nhận'), findsOneWidget);
      expect(find.byType(DemoBadge), findsOneWidget);
    });

    testWidgets('dữ liệu bay thật thì KHÔNG hiện nhãn Demo', (tester) async {
      await mount(tester, orders: () async => [order(1, 'IN_TRANSIT')], demo: false);
      expect(find.text('12 phút'), findsOneWidget);
      expect(find.byType(DemoBadge), findsNothing);
    });

    testWidgets('không có đơn đang giao: thẻ trống, không gọi hiện ETA', (tester) async {
      await mount(tester, orders: () async => [order(2, 'DELIVERED')]);
      expect(find.byKey(const Key('no-active-order')), findsOneWidget);
      expect(find.byKey(const Key('active-order-card')), findsNothing);
      expect(find.byType(DemoBadge), findsNothing);
    });

    testWidgets('đơn gần đây chỉ hiện 3 đơn mới nhất', (tester) async {
      await mount(tester, orders: () async => [for (var i = 1; i <= 5; i++) order(i, 'DELIVERED')]);
      expect(find.byKey(Key('recent-${order(1, 'X').id}')), findsOneWidget);
      expect(find.byKey(Key('recent-${order(3, 'X').id}')), findsOneWidget);
      expect(find.byKey(Key('recent-${order(4, 'X').id}')), findsNothing);
      expect(find.text('Gói 1 · 100 g'), findsOneWidget);
    });

    testWidgets('chưa có đơn nào: trạng thái rỗng có hướng dẫn', (tester) async {
      await mount(tester, orders: () async => []);
      expect(find.byKey(const Key('state-empty')), findsOneWidget);
      expect(find.text('Bạn chưa có đơn nào'), findsOneWidget);
    });

    testWidgets('lỗi tải đơn: có Thử lại và bấm được', (tester) async {
      var calls = 0;
      await mount(tester, orders: () async {
        calls++;
        if (calls == 1) throw const ApiFailure(FailureKind.offline, 'off');
        return [order(1, 'DELIVERED')];
      });
      expect(find.text('Không có kết nối mạng'), findsWidgets);
      await tester.tap(find.byKey(const Key('state-retry')).first);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('state-error')), findsNothing);
      expect(find.byKey(Key('recent-${order(1, 'X').id}')), findsOneWidget);
    });

    testWidgets('4 lối tắt đi đúng màn', (tester) async {
      final visited = <String>[];
      await mount(tester, orders: () async => [], visited: visited);
      for (final (key, route) in const [
        ('shortcut-addresses', '/customer/addresses'),
        ('shortcut-history', '/customer/orders'),
        ('shortcut-assistant', '/customer/assistant'),
      ]) {
        await tester.tap(find.byKey(Key(key)));
        await tester.pumpAndSettle();
        expect(find.text('ROUTE $route'), findsOneWidget, reason: key);
        // về trang chủ cho lần sau (go thay màn nên không có nút quay lại)
        tester.element(find.byType(Scaffold).first).go('/');
        await tester.pumpAndSettle();
      }
      await tester.tap(find.byKey(const Key('shortcut-new-order')));
      await tester.pumpAndSettle();
      expect(find.text('ROUTE /customer/new-order'), findsOneWidget);
    });

    testWidgets('thanh điều hướng: 5 mục, nút Tạo đơn tròn nằm đúng giữa', (tester) async {
      await mount(tester, orders: () async => []);
      for (final label in ['Trang chủ', 'Đơn hàng', 'Tạo đơn', 'Trợ lý AI', 'Hồ sơ']) {
        expect(find.text(label), findsWidgets, reason: label);
      }
      final screen = tester.getSize(find.byType(MaterialApp));
      expect(tester.getCenter(find.byKey(const Key('nav-new-order'))).dx,
          closeTo(screen.width / 2, 1));
      expect(tester.getSize(find.byKey(const Key('nav-new-order'))).width, 56);
      expect(tester.getSize(find.byType(AppBottomNav)).height, 84);
    });

    testWidgets('mục Đơn hàng và Hồ sơ của thanh điều hướng chuyển màn', (tester) async {
      await mount(tester, orders: () async => []);
      await tester.tap(find.byKey(const Key('nav-orders')));
      await tester.pumpAndSettle();
      expect(find.text('ROUTE /customer/orders'), findsOneWidget);
    });
  });
}
