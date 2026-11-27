import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/core/storage/storage_service.dart';
import 'package:smartdrone_mobile/core/theme/app_theme.dart';
import 'package:smartdrone_mobile/features/auth/session.dart';
import 'package:smartdrone_mobile/features/operator/operator_api.dart';
import 'package:smartdrone_mobile/features/operator/operator_controllers.dart';
import 'package:smartdrone_mobile/features/operator/operator_models.dart';
import 'package:smartdrone_mobile/features/operator/operator_screens.dart';
import 'package:smartdrone_mobile/features/operator/transit_controller.dart';
import 'package:smartdrone_mobile/features/orders/customer_order_models.dart';
import 'package:smartdrone_mobile/features/orders/order_detail_controller.dart';

import 'support/fakes.dart';
import 'support/pending_fakes.dart';

const mine = 'st-mine';
const other = 'st-other';

OrderSummary summary(int i, String status, {required String pickup, required String dropoff}) =>
    OrderSummary(
        id: '${i.toString().padLeft(8, '0')}-0000-0000-0000-000000000000',
        status: status,
        statusLabel: status,
        pickupStationId: pickup,
        dropoffStationId: dropoff,
        recipientName: 'Người $i',
        recipientAddressLine: 'Địa chỉ $i');

OrderDetail detail(String id, String status, int version) => OrderDetail.fromJson({
      'id': id,
      'version': version,
      'status': status,
      'statusLabel': 'Nhãn $status',
      'pickupStationId': mine,
      'dropoffStationId': other,
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

class TransitApi extends OperatorApi {
  TransitApi() : super(Dio());
  final prepared = <OrderSummary>[];
  final flying = <OrderSummary>[];
  final calls = <String>[];
  String liveStatus = 'PREPARING';
  int liveVersion = 3;
  ApiFailure? failure;

  OrderPage _page(List<OrderSummary> l) => OrderPage(items: l, page: 1, pageSize: 50, total: l.length);

  @override
  Future<List<AssignedStation>> assignedStations() async =>
      const [AssignedStation(id: mine, name: 'Trạm Của Tôi', status: 'ACTIVE', capacity: 4)];
  @override
  Future<OrderPage> listReceipts({int page = 1, int pageSize = 20, String? stationId}) async => _page([]);
  @override
  Future<OrderPage> listAwaitingPickup({int page = 1, int pageSize = 20, String? stationId}) async =>
      _page([]);
  @override
  Future<OrderPage> listReturning({int page = 1, int pageSize = 20, String? stationId}) async => _page([]);
  @override
  Future<OrderPage> listByStatus(String status, {int page = 1, int pageSize = 20}) async {
    calls.add('list $status');
    return _page(status == 'PREPARING' ? prepared : flying);
  }

  @override
  Future<OrderDetail> orderDetail(String orderId) async => detail(orderId, liveStatus, liveVersion);

  @override
  Future<OrderDetail> dispatch(String orderId, int version) async {
    calls.add('dispatch v=$version');
    if (failure != null) throw failure!;
    return detail(orderId, 'IN_TRANSIT', version + 1);
  }

  @override
  Future<OrderDetail> arrive(String orderId, int version) async {
    calls.add('arrive v=$version');
    if (failure != null) throw failure!;
    return detail(orderId, 'AWAITING_PICKUP', version + 1);
  }
}

void main() {
  late TransitApi api;
  setUp(() => api = TransitApi());

  test('nhãn nút theo trạng thái', () {
    expect(transitActionLabel('PREPARING'), 'Xuất phát');
    expect(transitActionLabel('IN_TRANSIT'), 'Drone đến');
    expect(transitActionLabel('SCHEDULED'), isNull);
    expect(transitActionLabel('DELIVERED'), isNull);
  });

  group('OperatorApi gửi đúng yêu cầu', () {
    test('dispatch/arrive: POST kèm If-Match theo phiên bản; danh sách theo trạng thái', () async {
      final seen = <RequestOptions>[];
      final dio = Dio(BaseOptions(baseUrl: 'http://x'))
        ..httpClientAdapter = FakeAdapter((o) {
          seen.add(o);
          if (o.method == 'GET') {
            return jsonResponse({'items': <dynamic>[], 'page': 1, 'pageSize': 20, 'total': 0});
          }
          return jsonResponse({
            'id': 'x',
            'version': 5,
            'status': 'IN_TRANSIT',
            'recipient': {'name': 'a', 'phone': '1', 'addressLine': 'b'},
            'package': {
              'description': 'g',
              'weightGrams': 1,
              'lengthCm': 1,
              'widthCm': 1,
              'heightCm': 1,
              'photoFileIds': <String>[]
            },
          });
        });
      final real = OperatorApi(dio);
      await real.dispatch('x', 4);
      await real.arrive('x', 5);
      await real.listByStatus('PREPARING', page: 2);
      expect(seen[0].path, '/v1/orders/x/dispatch');
      expect(seen[0].headers['If-Match'], '"4"');
      expect(seen[1].path, '/v1/orders/x/arrive');
      expect(seen[1].headers['If-Match'], '"5"');
      expect(seen[2].queryParameters['status'], 'PREPARING');
      expect(seen[2].queryParameters['page'], 2);
    });
  });

  group('bộ điều khiển', () {
    ProviderContainer make() {
      final c = ProviderContainer(overrides: [
        ...pendingOverrides(MemoryPendingStore()),
        operatorApiProvider.overrideWithValue(api),
        operatorUserProvider.overrideWithValue('op-1'),
      ]);
      addTearDown(c.dispose);
      c.listen(transitListProvider, (_, __) {});
      c.listen(transitActionProvider, (_, __) {});
      return c;
    }

    test('danh sách: PREPARING chỉ giữ trạm GỬI của mình, IN_TRANSIT chỉ giữ trạm NHẬN của mình', () async {
      api.prepared.addAll([
        summary(1, 'PREPARING', pickup: mine, dropoff: other),
        summary(2, 'PREPARING', pickup: other, dropoff: mine), // trạm gửi không phải của mình
      ]);
      api.flying.addAll([
        summary(3, 'IN_TRANSIT', pickup: other, dropoff: mine),
        summary(4, 'IN_TRANSIT', pickup: mine, dropoff: other), // trạm nhận không phải của mình
      ]);
      final c = make();
      await Future<void>.delayed(const Duration(milliseconds: 20));
      final ids = [for (final o in c.read(transitListProvider).items) o.id.substring(0, 8)];
      expect(ids, ['00000003', '00000001']);
    });

    test('Xuất phát: đọc lại đơn rồi gửi đúng phiên bản mới nhất', () async {
      api.liveVersion = 7;
      final c = make();
      final r = await c.read(transitActionProvider.notifier).run('o1', 'PREPARING');
      expect(r.ok, isTrue);
      expect(api.calls.where((e) => e.startsWith('dispatch')), ['dispatch v=7']);
    });

    test('Drone đến gọi arrive, không gọi dispatch', () async {
      api.liveStatus = 'IN_TRANSIT';
      final c = make();
      final r = await c.read(transitActionProvider.notifier).run('o1', 'IN_TRANSIT');
      expect(r.ok, isTrue);
      expect(api.calls.any((e) => e.startsWith('arrive')), isTrue);
      expect(api.calls.any((e) => e.startsWith('dispatch')), isFalse);
    });

    test('đơn đã đổi trạng thái: KHÔNG gửi, báo CHƯA thực hiện', () async {
      api.liveStatus = 'IN_TRANSIT'; // người khác đã xuất phát rồi
      final c = make();
      final r = await c.read(transitActionProvider.notifier).run('o1', 'PREPARING');
      expect(r.ok, isFalse);
      expect(r.message, contains('CHƯA'));
      expect(api.calls.any((e) => e.startsWith('dispatch')), isFalse);
    });

    test('server từ chối 412: báo CHƯA thực hiện', () async {
      api.failure = const ApiFailure(FailureKind.versionConflict, 'cũ', status: 412);
      final c = make();
      final r = await c.read(transitActionProvider.notifier).run('o1', 'PREPARING');
      expect(r.ok, isFalse);
      expect(r.message, contains('CHƯA'));
    });

    test('mất mạng: báo lỗi của máy chủ, không xếp hàng offline', () async {
      api.failure = const ApiFailure(FailureKind.offline, 'Không có mạng');
      final c = make();
      final r = await c.read(transitActionProvider.notifier).run('o1', 'PREPARING');
      expect(r.ok, isFalse);
      expect(r.message, 'Không có mạng');
    });

    test('bấm đúp: lần gọi thứ hai khi đang chạy bị bỏ qua', () async {
      final c = make();
      final a = c.read(transitActionProvider.notifier).run('o1', 'PREPARING');
      final b = await c.read(transitActionProvider.notifier).run('o1', 'PREPARING');
      await a;
      expect(b.message, isEmpty);
      expect(api.calls.where((e) => e.startsWith('dispatch')).length, 1);
    });
  });

  group('màn hình', () {
    Future<void> mount(WidgetTester tester) async {
      tester.view.physicalSize = const Size(1170, 2532);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      final session = makeSession(
          store: MemoryTokens()..value = const Tokens('a', 'r'),
          api: FakeAdapter((_) => jsonResponse(profile(role: 'STATION_OPERATOR', name: 'Vận Hành'))),
          auth: FakeAdapter((_) => jsonResponse({})));
      await tester.runAsync(session.restore);
      final router = GoRouter(routes: [
        GoRoute(path: '/', builder: (_, __) => const OperatorHomeScreen()),
        GoRoute(path: '/profile', builder: (_, __) => const Scaffold(body: Text('PROFILE'))),
      ]);
      await tester.pumpWidget(ProviderScope(
          overrides: [
            ...pendingOverrides(MemoryPendingStore()),
            sessionProvider.overrideWith((ref) => session),
            operatorApiProvider.overrideWithValue(api),
            stationsProvider.overrideWith((ref) async => const [
                  Station(id: mine, name: 'Trạm Của Tôi', latitude: 0, longitude: 0, status: 'ACTIVE'),
                  Station(id: other, name: 'Trạm Khác', latitude: 0, longitude: 0, status: 'ACTIVE'),
                ]),
          ],
          child: MaterialApp.router(theme: buildAppTheme(), routerConfig: router)));
      await tester.pumpAndSettle();
    }

    testWidgets('tab "Đang giao" hiện số đơn và nút theo từng trạng thái', (tester) async {
      api.prepared.add(summary(1, 'PREPARING', pickup: mine, dropoff: other));
      api.flying.add(summary(3, 'IN_TRANSIT', pickup: other, dropoff: mine));
      await mount(tester);
      expect(find.text('Đang giao (2)'), findsOneWidget);
      await tester.tap(find.byKey(const Key('tab-transit')));
      await tester.pumpAndSettle();
      expect(find.text('Xuất phát'), findsOneWidget);
      expect(find.text('Drone đến'), findsOneWidget);
    });

    testWidgets('Xuất phát: hỏi xác nhận; Quay lại thì không gọi API; xác nhận thì gửi và báo kết quả',
        (tester) async {
      api.prepared.add(summary(1, 'PREPARING', pickup: mine, dropoff: other));
      await mount(tester);
      await tester.tap(find.byKey(const Key('tab-transit')));
      await tester.pumpAndSettle();
      final key = Key('action-${summary(1, 'PREPARING', pickup: mine, dropoff: other).id}');
      await tester.tap(find.byKey(key));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('transit-no')));
      await tester.pumpAndSettle();
      expect(api.calls.any((e) => e.startsWith('dispatch')), isFalse);
      await tester.tap(find.byKey(key));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('transit-yes')));
      await tester.pumpAndSettle();
      expect(api.calls.where((e) => e.startsWith('dispatch')), ['dispatch v=3']);
      expect(find.text('Đã xuất phát, đơn đang giao.'), findsOneWidget);
    });

    testWidgets('lỗi từ máy chủ hiện trên thanh thông báo và đơn vẫn còn để thử lại', (tester) async {
      api.prepared.add(summary(1, 'PREPARING', pickup: mine, dropoff: other));
      api.failure = const ApiFailure(FailureKind.forbidden, 'Không có quyền');
      await mount(tester);
      await tester.tap(find.byKey(const Key('tab-transit')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(Key('action-${summary(1, 'PREPARING', pickup: mine, dropoff: other).id}')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('transit-yes')));
      await tester.pumpAndSettle();
      expect(find.text('Không có quyền'), findsOneWidget);
      expect(find.text('Xuất phát'), findsOneWidget);
    });
  });
}
