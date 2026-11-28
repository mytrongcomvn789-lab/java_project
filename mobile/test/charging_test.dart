import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/core/theme/app_theme.dart';
import 'package:smartdrone_mobile/features/operator/charging_api.dart';
import 'package:smartdrone_mobile/features/operator/charging_screens.dart';
import 'package:smartdrone_mobile/features/operator/operator_api.dart';
import 'package:smartdrone_mobile/features/operator/operator_controllers.dart';
import 'package:smartdrone_mobile/features/operator/operator_models.dart';

import 'support/fakes.dart';

Map<String, dynamic> chargerJson(String code, String state,
        {String? droneCode, int? pct, bool canRelease = false, bool online = true}) =>
    {
      'id': 'id-$code',
      'code': code,
      'state': state,
      'online': online,
      'lastHeartbeatAt': '2026-11-28T03:29:00Z',
      if (droneCode != null) 'drone': {'id': 'd-$droneCode', 'code': droneCode},
      'batteryPercent': pct,
      'startPercent': droneCode == null ? null : 20,
      'targetPercent': droneCode == null ? null : 100,
      'chargedMinutes': droneCode == null ? null : 33,
      'remainingMinutes': state == 'CHARGING' ? 47 : (droneCode == null ? null : 0),
      'readyAt': droneCode == null ? null : '2026-11-28T04:16:00Z',
      'temperatureC': droneCode == null ? null : 33.0,
      'canRelease': canRelease,
    };

Map<String, dynamic> stationJson({Object? nextSlotAt}) => {
      'stationId': 's1',
      'stationName': 'Trạm Bến Thành',
      'stationStatus': 'ACTIVE',
      'summary': {'total': 4, 'empty': 1, 'charging': 1, 'full': 1, 'maintenance': 1, 'online': 3},
      'chargers': [
        chargerJson('A-01', 'CHARGING', droneCode: 'DR-A1', pct: 53),
        chargerJson('A-02', 'FULL', droneCode: 'DR-A3', pct: 100, canRelease: true),
        chargerJson('A-03', 'EMPTY'),
        chargerJson('A-04', 'MAINTENANCE', online: false),
      ],
      'idleDrones': [
        {'id': 'x', 'code': 'DR-A4', 'batteryPercent': 96}
      ],
      'nextSlotAt': nextSlotAt,
      'source': 'SIMULATED',
      'generatedAt': '2026-11-28T03:30:00Z',
    };

class FakeChargingApi extends ChargingApi {
  FakeChargingApi() : super(Dio());
  Map<String, dynamic> station = stationJson();
  final calls = <String>[];
  ApiFailure? failure;

  /// Lỗi chỉ áp cho thao tác ghi (cho drone rời, bảo trì, phân công), không áp cho việc tải.
  ApiFailure? actionFailure;
  Map<String, dynamic> boardJson = {
    'fleet': [
      {'id': 'f1', 'code': 'DR-A1', 'status': 'CHARGING', 'batteryPercent': 53, 'stationName': 'Trạm Bến Thành'},
      {'id': 'f2', 'code': 'DR-B2', 'status': 'IN_FLIGHT', 'batteryPercent': 71, 'stationName': ''},
    ],
    'orders': [
      {
        'orderId': 'aaaaaaaa-0000-0000-0000-000000000001',
        'orderStatus': 'PREPARING',
        'pickupStationName': 'Trạm Bến Thành',
        'dropoffStationName': 'Trạm Chợ Lớn',
        'dispatch': {
          'orderId': 'aaaaaaaa-0000-0000-0000-000000000001',
          'stage': 'NO_DRONE',
          'assigned': false,
          'waitMinutes': 25,
          'waitReason': 'Chờ drone sạc đủ pin',
          'message': 'Chưa có drone phù hợp'
        }
      },
      {
        'orderId': 'bbbbbbbb-0000-0000-0000-000000000002',
        'orderStatus': 'IN_TRANSIT',
        'pickupStationName': 'Trạm Thủ Đức',
        'dropoffStationName': 'Trạm Bến Thành',
        'dispatch': {
          'orderId': 'bbbbbbbb-0000-0000-0000-000000000002',
          'stage': 'IN_FLIGHT',
          'assigned': true,
          'droneCode': 'DR-B2',
          'originStationName': 'Trạm Thủ Đức',
          'reservedChargerCode': 'A-03',
          'plannedDepartureAt': '2026-11-28T03:00:00Z',
          'plannedArrivalAt': '2026-11-28T03:20:00Z'
        }
      }
    ],
    'source': 'FORMULA'
  };

  @override
  Future<StationChargers> stationChargers(String stationId) async {
    calls.add('get $stationId');
    if (failure != null) throw failure!;
    return StationChargers.fromJson(station);
  }

  @override
  Future<StationChargers> release(String stationId, String chargerId) async {
    calls.add('release $chargerId');
    if (actionFailure != null) throw actionFailure!;
    station = stationJson();
    (station['chargers'] as List)[1] = chargerJson('A-02', 'EMPTY');
    return StationChargers.fromJson(station);
  }

  @override
  Future<StationChargers> setMaintenance(String stationId, String chargerId, bool maintenance) async {
    calls.add('maintenance $chargerId $maintenance');
    if (actionFailure != null) throw actionFailure!;
    return StationChargers.fromJson(station);
  }

  @override
  Future<DispatchBoard> board() async => DispatchBoard.fromJson(boardJson);

  @override
  Future<DispatchPlan> assign(String orderId) async {
    calls.add('assign $orderId');
    if (actionFailure != null) throw actionFailure!;
    return DispatchPlan.fromJson({'orderId': orderId, 'stage': 'ASSIGNED', 'assigned': true});
  }
}

class FakeOperatorApi extends OperatorApi {
  FakeOperatorApi() : super(Dio());
  @override
  Future<List<AssignedStation>> assignedStations() async => const [
        AssignedStation(
            id: 's1',
            name: 'Trạm Bến Thành',
            status: 'ACTIVE',
            address: '1 Lê Lợi',
            capacity: 4,
            slotUsed: 1,
            connection: 'ONLINE'),
        AssignedStation(id: 's2', name: 'Trạm Thủ Đức', status: 'ACTIVE', connection: 'UNKNOWN'),
      ];
}

void main() {
  group('định dạng', () {
    test('thời lượng và giờ', () {
      expect(minutesLabel(null), '—');
      expect(minutesLabel(45), '45 phút');
      expect(minutesLabel(60), '1 giờ');
      expect(minutesLabel(65), '1 giờ 5 phút');
      expect(hm(null), '—');
    });
    test('mọi cặp màu trạng thái thiết bị đủ tương phản', () {
      double c(ChipColors x) {
        final a = x.foreground.computeLuminance(), b = x.background.computeLuminance();
        return ((a > b ? a : b) + 0.05) / ((a > b ? b : a) + 0.05);
      }

      for (final s in ChargerState.values) {
        expect(c(chargerColors(s)), greaterThanOrEqualTo(4.5), reason: '$s');
      }
    });
  });

  group('model', () {
    test('đọc đủ thiết bị, drone, tóm tắt và nguồn giả lập', () {
      final d = StationChargers.fromJson(stationJson());
      expect(d.simulated, isTrue);
      expect(d.summary.charging, 1);
      expect(d.chargers.length, 4);
      expect(d.chargers[0].drone?.code, 'DR-A1');
      expect(d.chargers[0].state, ChargerState.charging);
      expect(d.chargers[1].canRelease, isTrue);
      expect(d.chargers[2].drone, isNull);
      expect(d.idleDrones.single.code, 'DR-A4');
      expect(d.nextSlotAt, isNull);
    });
    test('trạng thái lạ không làm hỏng, thành "không rõ"', () {
      expect(Charger.fromJson(chargerJson('X', 'NEW_STATE')).state, ChargerState.unknown);
    });
    test('bảng điều phối đọc đội drone và kế hoạch từng đơn', () {
      final b = DispatchBoard.fromJson(FakeChargingApi().boardJson);
      expect(b.fleet.length, 2);
      expect(b.orders.first.plan.stage, 'NO_DRONE');
      expect(b.orders.first.shortCode, 'AAAAAAAA');
      expect(b.orders.last.plan.reservedChargerCode, 'A-03');
    });
  });

  group('ChargingApi gửi đúng yêu cầu', () {
    test('đường dẫn và nội dung', () async {
      final seen = <RequestOptions>[];
      final dio = Dio(BaseOptions(baseUrl: 'http://x'))
        ..httpClientAdapter = FakeAdapter((o) {
          seen.add(o);
          if (o.path.endsWith('/board')) return jsonResponse({'fleet': <dynamic>[], 'orders': <dynamic>[]});
          if (o.path.endsWith('/dispatch-plan')) return jsonResponse({'orderId': 'o', 'stage': 'ASSIGNED'});
          return jsonResponse(stationJson());
        });
      final api = ChargingApi(dio);
      await api.stationChargers('s1');
      await api.release('s1', 'c1');
      await api.setMaintenance('s1', 'c1', true);
      await api.board();
      await api.assign('o1');
      expect(seen[0].path, '/v1/stations/s1/chargers');
      expect((seen[1].method, seen[1].path), ('POST', '/v1/stations/s1/chargers/c1/release'));
      expect((seen[2].method, seen[2].path), ('PATCH', '/v1/stations/s1/chargers/c1/maintenance'));
      expect(seen[2].data, {'maintenance': true});
      expect(seen[3].path, '/v1/dispatch/board');
      expect((seen[4].method, seen[4].path), ('POST', '/v1/orders/o1/dispatch-plan'));
    });
  });

  group('màn hình', () {
    late FakeChargingApi api;

    Future<void> mount(WidgetTester tester, {String initial = '/operator/stations/s1'}) async {
      tester.view.physicalSize = const Size(1170, 3000);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      final router = GoRouter(initialLocation: initial, routes: [
        GoRoute(
            path: '/operator/stations',
            builder: (_, __) => const StationsScreen(),
            routes: [
              GoRoute(
                  path: ':id',
                  builder: (_, s) => StationChargersScreen(stationId: s.pathParameters['id']!)),
            ]),
        GoRoute(path: '/operator/fleet', builder: (_, __) => const FleetScreen()),
        GoRoute(path: '/operator', builder: (_, __) => const Scaffold(body: Text('HOME'))),
      ]);
      await tester.pumpWidget(ProviderScope(
          overrides: [
            chargingApiProvider.overrideWithValue(api),
            operatorApiProvider.overrideWithValue(FakeOperatorApi()),
            operatorUserProvider.overrideWithValue('op-1'),
          ],
          child: MaterialApp.router(theme: buildAppTheme(), routerConfig: router)));
      await tester.pumpAndSettle();
    }

    setUp(() => api = FakeChargingApi());

    testWidgets('danh sách trạm: kết nối, sức chứa; bấm vào mở chi tiết', (tester) async {
      await mount(tester, initial: '/operator/stations');
      expect(find.text('Trạm Bến Thành'), findsOneWidget);
      expect(find.text('Online'), findsOneWidget);
      expect(find.text('Chưa có dữ liệu'), findsWidgets, reason: 'không giả vờ online khi không có tín hiệu');
      expect(find.text('Offline'), findsNothing);
      await tester.tap(find.byKey(const Key('station-s1')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('charger-A-01')), findsOneWidget);
    });

    testWidgets('chi tiết trạm: đếm theo trạng thái, lưới thiết bị, drone đang đỗ, ghi chú giả lập', (tester) async {
      await mount(tester);
      expect(tester.widget<Text>(find.byKey(const Key('count-charging'))).data, '1');
      expect(tester.widget<Text>(find.byKey(const Key('count-maintenance'))).data, '1');
      expect(find.text('3/4 thiết bị đang gửi tín hiệu'), findsOneWidget);
      expect(find.byKey(const Key('sim-note')), findsOneWidget);
      expect(find.text('53%'), findsOneWidget);
      expect(find.text('DR-A1'), findsOneWidget);
      expect(find.byKey(const Key('idle-DR-A4')), findsOneWidget);
      expect(find.byKey(const Key('next-slot')), findsNothing);
    });

    testWidgets('hết chỗ nhận drone: hiện giờ dự kiến có chỗ', (tester) async {
      api.station = stationJson(nextSlotAt: '2026-11-28T04:30:00Z');
      await mount(tester);
      expect(find.byKey(const Key('next-slot')), findsOneWidget);
    });

    testWidgets('thiết bị đang sạc: chi tiết đúng, nút cho rời bị tắt và nói rõ lý do', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('charger-A-01')));
      await tester.pumpAndSettle();
      expect(find.text('Thiết bị A-01'), findsOneWidget);
      expect(find.text('Đang gửi tín hiệu'), findsOneWidget);
      expect(find.text('53% (từ 20% lên 100%)'), findsOneWidget);
      expect(find.text('Chưa sạc đầy, chưa cho rời'), findsOneWidget);
      expect(tester.widget<FilledButton>(find.byKey(const Key('release-drone'))).onPressed, isNull);
      expect(find.byKey(const Key('maintenance-hint')), findsOneWidget, reason: 'còn drone thì không bảo trì');
      expect(find.byKey(const Key('toggle-maintenance')), findsNothing);
    });

    testWidgets('thiết bị đầy: cho drone rời gọi đúng API rồi bảng cập nhật', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('charger-A-02')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('release-drone')));
      await tester.pumpAndSettle();
      expect(api.calls, contains('release id-A-02'));
      expect(find.text('Drone đã rời thiết bị A-02.'), findsOneWidget);
    });

    testWidgets('thiết bị trống: bật bảo trì; thiết bị bảo trì: đưa vào hoạt động lại', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('charger-A-03')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('toggle-maintenance')));
      await tester.pumpAndSettle();
      expect(api.calls, contains('maintenance id-A-03 true'));
    });

    testWidgets('thiết bị bảo trì: hiện nút đưa vào hoạt động lại và gửi maintenance=false', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('charger-A-04')));
      await tester.pumpAndSettle();
      expect(find.text('Mất tín hiệu'), findsOneWidget);
      expect(find.text('Đưa vào hoạt động lại'), findsOneWidget);
      await tester.tap(find.byKey(const Key('toggle-maintenance')));
      await tester.pumpAndSettle();
      expect(api.calls, contains('maintenance id-A-04 false'));
    });

    testWidgets('server từ chối: hiện lời giải thích của server, không báo thành công', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('charger-A-02')));
      await tester.pumpAndSettle();
      api.actionFailure = const ApiFailure(FailureKind.validation, 'Chỉ cho drone rời khi pin đã đạt mức yêu cầu');
      await tester.tap(find.byKey(const Key('release-drone')));
      await tester.pumpAndSettle();
      expect(find.text('Chỉ cho drone rời khi pin đã đạt mức yêu cầu'), findsOneWidget);
      expect(find.text('Drone đã rời thiết bị A-02.'), findsNothing);
    });

    testWidgets('lỗi tải chi tiết: báo lỗi và Thử lại phục hồi được', (tester) async {
      api.failure = const ApiFailure(FailureKind.offline, 'Không có mạng');
      await mount(tester);
      expect(find.byKey(const Key('state-error')), findsOneWidget);
      api.failure = null;
      await tester.tap(find.byKey(const Key('state-retry')).first);
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('charger-A-01')), findsOneWidget);
    });

    testWidgets('đội drone: kế hoạch từng đơn, nút phân công chỉ cho đơn chưa có drone', (tester) async {
      await mount(tester, initial: '/operator/fleet');
      expect(find.byKey(const Key('fleet-note')), findsOneWidget);
      expect(find.text('Chưa có drone phù hợp'), findsWidgets);
      expect(find.byKey(const Key('assign-AAAAAAAA')), findsOneWidget);
      expect(find.byKey(const Key('assign-BBBBBBBB')), findsNothing, reason: 'đơn đang bay không phân công lại');
      expect(find.textContaining('Drone DR-B2 từ Trạm Thủ Đức'), findsOneWidget);
      expect(find.textContaining('sạc A-03'), findsOneWidget);
      expect(find.text('DR-B2'), findsWidgets);
      expect(find.textContaining('Đang bay'), findsWidgets);
      await tester.tap(find.byKey(const Key('assign-AAAAAAAA')));
      await tester.pumpAndSettle();
      expect(api.calls, contains('assign aaaaaaaa-0000-0000-0000-000000000001'));
    });
  });
}
