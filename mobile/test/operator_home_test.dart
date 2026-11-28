import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/storage/storage_service.dart';
import 'package:smartdrone_mobile/core/theme/app_theme.dart';
import 'package:smartdrone_mobile/core/ui/app_widgets.dart';
import 'package:smartdrone_mobile/features/auth/session.dart';
import 'package:smartdrone_mobile/features/operator/operator_api.dart';
import 'package:smartdrone_mobile/features/operator/operator_controllers.dart';
import 'package:smartdrone_mobile/features/operator/operator_models.dart';
import 'package:smartdrone_mobile/features/operator/operator_screens.dart';
import 'package:smartdrone_mobile/features/operator/pickup_confirm_controller.dart';
import 'package:smartdrone_mobile/features/orders/customer_order_models.dart';
import 'package:smartdrone_mobile/features/orders/order_detail_controller.dart';

import 'support/fakes.dart';
import 'support/pending_fakes.dart';

const mine = 'st-mine';

OrderSummary order(int i, String status, {String pickup = mine, String dropoff = 'st-other'}) =>
    OrderSummary(
        id: '${i.toString().padLeft(8, '0')}-0000-0000-0000-000000000000',
        status: status,
        statusLabel: status,
        pickupStationId: pickup,
        dropoffStationId: dropoff,
        recipientName: 'Người $i',
        recipientAddressLine: 'Địa chỉ $i');

class HomeApi extends OperatorApi {
  HomeApi() : super(Dio());
  List<AssignedStation> stations = const [
    AssignedStation(
        id: mine,
        name: 'Trạm Demo A',
        status: 'ACTIVE',
        address: '1 Lê Lợi, Quận 1',
        capacity: 10,
        slotUsed: 6)
  ];
  List<OrderSummary> receipts = [order(1, 'SCHEDULED'), order(2, 'SCHEDULED')];
  List<OrderSummary> pickups = [order(3, 'AWAITING_PICKUP', pickup: 'st-other', dropoff: mine)];
  List<OrderSummary> returns = [];

  OrderPage _page(List<OrderSummary> l) =>
      OrderPage(items: l, page: 1, pageSize: 50, total: l.length);

  @override
  Future<List<AssignedStation>> assignedStations() async => stations;
  @override
  Future<OrderPage> listReceipts({int page = 1, int pageSize = 20, String? stationId}) async =>
      _page(receipts);
  @override
  Future<OrderPage> listAwaitingPickup({int page = 1, int pageSize = 20, String? stationId}) async =>
      _page(pickups);
  @override
  Future<OrderPage> listReturning({int page = 1, int pageSize = 20, String? stationId}) async =>
      _page(returns);
}

/// Độ tương phản WCAG giữa hai màu.
double contrast(Color a, Color b) {
  final l1 = a.computeLuminance(), l2 = b.computeLuminance();
  final hi = l1 > l2 ? l1 : l2, lo = l1 > l2 ? l2 : l1;
  return (hi + 0.05) / (lo + 0.05);
}

void main() {
  late HomeApi api;

  setUp(() => api = HomeApi());

  group('design token', () {
    test('mọi cặp màu chip trạng thái đạt tương phản ≥ 4.5:1', () {
      for (final c in [
        StatusPalette.pending,
        StatusPalette.active,
        StatusPalette.done,
        StatusPalette.failed,
        StatusPalette.closed,
      ]) {
        expect(contrast(c.foreground, c.background), greaterThanOrEqualTo(4.5),
            reason: '${c.foreground} trên ${c.background}');
      }
    });

    test('chữ chính và chữ phụ đủ tương phản trên nền app và thẻ', () {
      for (final bg in [AppColors.background, AppColors.surface]) {
        expect(contrast(AppColors.text, bg), greaterThanOrEqualTo(4.5));
        expect(contrast(AppColors.textMuted, bg), greaterThanOrEqualTo(4.5));
      }
      expect(contrast(Colors.white, AppColors.primary), greaterThanOrEqualTo(4.5));
    });

    test('12 trạng thái đơn đều có nhóm màu', () {
      for (final s in const [
        'PENDING', 'APPROVED', 'REJECTED', 'SCHEDULED', 'PREPARING', 'IN_TRANSIT',
        'AWAITING_PICKUP', 'DELIVERED', 'CANCELLED', 'FAILED', 'RETURNING', 'RETURNED',
      ]) {
        expect(StatusPalette.of(s), isNotNull);
      }
      expect(StatusPalette.of('FAILED'), StatusPalette.failed);
      expect(StatusPalette.of('DELIVERED'), StatusPalette.done);
      expect(StatusPalette.of('PENDING'), StatusPalette.pending);
    });
  });

  group('mã vừa quét ở thanh điều hướng', () {
    test('màn xác nhận giao lấy mã một lần rồi xóa, không dùng lại cho đơn khác', () async {
      final container = ProviderContainer(overrides: [
        ...pendingOverrides(MemoryPendingStore()),
        operatorApiProvider.overrideWithValue(api),
        operatorUserProvider.overrideWithValue('op-1'),
      ]);
      addTearDown(container.dispose);
      container.listen(scannedCredentialProvider, (_, __) {});
      container.read(scannedCredentialProvider.notifier).state =
          PickupCredential.otp('123456');
      final sub = container.listen(pickupConfirmProvider('o1'), (_, __) {});
      expect(container.read(pickupConfirmProvider('o1')).credential?.value, '123456');
      await Future<void>.delayed(const Duration(milliseconds: 10));
      expect(container.read(scannedCredentialProvider), isNull);
      sub.close();
      container.listen(pickupConfirmProvider('o2'), (_, __) {});
      expect(container.read(pickupConfirmProvider('o2')).credential, isNull);
    });
  });

  group('màn hình', () {
    late Session session;

    Future<ProviderContainer> mount(WidgetTester tester) async {
      tester.view.physicalSize = const Size(1170, 2532);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      final tokens = MemoryTokens()..value = const Tokens('a', 'r');
      session = makeSession(
          store: tokens,
          api: FakeAdapter((_) => jsonResponse(profile(role: 'STATION_OPERATOR', name: 'Vận Hành'))),
          auth: FakeAdapter((_) => jsonResponse({})));
      await tester.runAsync(session.restore);
      final router = GoRouter(routes: [
        GoRoute(path: '/', builder: (_, __) => const OperatorHomeScreen()),
        GoRoute(
            path: '/operator/receipts/:id',
            builder: (_, s) => Scaffold(body: Text('RECEIPT ${s.pathParameters['id']}'))),
        GoRoute(path: '/profile', builder: (_, __) => const Scaffold(body: Text('PROFILE'))),
        GoRoute(
            path: '/operator/stations',
            builder: (_, __) => const Scaffold(body: Text('STATIONS SCREEN'))),
      ]);
      await tester.pumpWidget(ProviderScope(
          overrides: [
            ...pendingOverrides(MemoryPendingStore()),
            sessionProvider.overrideWith((ref) => session),
            operatorApiProvider.overrideWithValue(api),
            stationsProvider.overrideWith((ref) async => const [
                  Station(id: mine, name: 'Trạm Demo A', latitude: 0, longitude: 0, status: 'ACTIVE'),
                  Station(id: 'st-other', name: 'Trạm Khác', latitude: 0, longitude: 0, status: 'ACTIVE'),
                ]),
          ],
          child: MaterialApp.router(theme: buildAppTheme(), routerConfig: router)));
      await tester.pumpAndSettle();
      return ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
    }

    testWidgets('header: tên trạm, thống kê thật và thanh sức chứa 6/10', (tester) async {
      await mount(tester);
      expect(find.text('Trạm làm việc'), findsOneWidget);
      expect(find.descendant(of: find.byKey(const Key('station-selector')), matching: find.text('Trạm Demo A')),
          findsOneWidget);
      expect(find.text('6/10'), findsOneWidget);
      expect(find.text('Chờ nhận (2)'), findsOneWidget);
      expect(find.text('Chờ giao (1)'), findsOneWidget);
      expect(find.text('Thất bại (0)'), findsOneWidget);
    });

    testWidgets('kết nối: chưa có nguồn heartbeat thì KHÔNG hiện Online', (tester) async {
      await mount(tester);
      expect(find.descendant(of: find.byKey(const Key('connection-chip')), matching: find.text('Chưa có dữ liệu')),
          findsOneWidget);
      expect(find.text('Online'), findsNothing);
    });

    testWidgets('kết nối: ONLINE hiện Online, có trạm OFFLINE thì hiện Offline', (tester) async {
      api.stations = const [
        AssignedStation(id: mine, name: 'A', status: 'ACTIVE', connection: 'ONLINE', capacity: 4)
      ];
      await mount(tester);
      expect(find.text('Online'), findsOneWidget);
    });

    testWidgets('có trạm OFFLINE: hiện Offline dù trạm khác online', (tester) async {
      api.stations = const [
        AssignedStation(id: mine, name: 'A', status: 'ACTIVE', connection: 'ONLINE'),
        AssignedStation(id: 'b', name: 'B', status: 'ACTIVE', connection: 'OFFLINE'),
      ];
      await mount(tester);
      expect(find.text('Offline'), findsOneWidget);
      expect(find.text('2 trạm'), findsOneWidget);
    });

    testWidgets('chưa được phân công trạm: hiện cảnh báo và không có thanh sức chứa số liệu', (tester) async {
      api.stations = const [];
      await mount(tester);
      expect(find.byKey(const Key('stations-banner')), findsOneWidget);
      expect(find.textContaining('chưa được phân công trạm nào'), findsOneWidget);
      expect(find.text('Chưa có dữ liệu'), findsWidgets);
    });

    testWidgets('mỗi thẻ có nút hành động chính, bấm mở màn chi tiết', (tester) async {
      await mount(tester);
      expect(find.text('Nhận kiện'), findsNWidgets(2));
      await tester.tap(find.byKey(Key('action-${order(1, 'SCHEDULED').id}')));
      await tester.pumpAndSettle();
      expect(find.textContaining('RECEIPT 00000001'), findsOneWidget);
    });

    testWidgets('thanh điều hướng: nút Quét QR tròn nằm đúng giữa màn hình', (tester) async {
      await mount(tester);
      final screen = tester.getSize(find.byType(MaterialApp));
      final scan = tester.getCenter(find.byKey(const Key('nav-scan')));
      expect(scan.dx, closeTo(screen.width / 2, 1));
      expect(tester.getSize(find.byKey(const Key('nav-scan'))).width, 56);
      expect(tester.getSize(find.byType(AppBottomNav)).height, 84);
      expect(find.text('Đơn cần xử lý'), findsOneWidget);
      expect(find.text('Trạm'), findsOneWidget);
      expect(find.text('Hồ sơ'), findsOneWidget);
    });

    testWidgets('nút Trạm trên thanh dưới mở màn trạm và thiết bị sạc', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('nav-stations')));
      await tester.pumpAndSettle();
      expect(find.text('STATIONS SCREEN'), findsOneWidget);
    });

    testWidgets('ô chọn trạm trên đầu trang mở bảng trạm có địa chỉ và sức chứa', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('station-selector')));
      await tester.pumpAndSettle();
      expect(find.text('Trạm của bạn'), findsOneWidget);
      expect(find.text('1 Lê Lợi, Quận 1'), findsOneWidget);
      expect(find.text('Hoạt động'), findsOneWidget);
    });

    testWidgets('nút Hồ sơ chuyển sang màn hồ sơ', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('nav-profile')));
      await tester.pumpAndSettle();
      expect(find.text('PROFILE'), findsOneWidget);
    });

    testWidgets('chưa tải được trạm: báo lỗi và có nút Thử lại', (tester) async {
      api = HomeApi();
      final failing = _FailingStationsApi();
      api = failing;
      await mount(tester);
      expect(find.byKey(const Key('stations-error')), findsOneWidget);
      failing.fail = false;
      await tester.tap(find.text('Thử lại'));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('stations-error')), findsNothing);
    });
  });
}

class _FailingStationsApi extends HomeApi {
  bool fail = true;
  @override
  Future<List<AssignedStation>> assignedStations() async {
    if (fail) {
      throw DioException.connectionError(requestOptions: RequestOptions(), reason: 'off');
    }
    return stations;
  }
}
