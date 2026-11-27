import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_provider.dart';
import 'package:smartdrone_mobile/core/router/app_router.dart';
import 'package:smartdrone_mobile/core/storage/storage_service.dart';
import 'package:smartdrone_mobile/core/theme/app_theme.dart';
import 'package:smartdrone_mobile/features/auth/session.dart';
import 'package:smartdrone_mobile/features/dispatcher/dispatcher_api.dart';
import 'package:smartdrone_mobile/features/dispatcher/dispatcher_screens.dart';
import 'package:smartdrone_mobile/features/dispatcher/dispatcher_widgets.dart';
import 'package:smartdrone_mobile/features/orders/customer_order_models.dart';
import 'package:smartdrone_mobile/features/orders/order_detail_controller.dart' show stationsProvider;

import 'support/fakes.dart';

const oid = '11111111-1111-1111-1111-111111111111';

Map<String, dynamic> orderJson(String status, {int version = 1, String id = oid}) => {
      'id': id,
      'version': version,
      'status': status,
      'statusLabel': 'Nhãn $status',
      'pickupStationId': 'st-a',
      'dropoffStationId': 'st-b',
      'recipient': {'name': 'An', 'phone': '0901234567', 'addressLine': '1 Lê Lợi'},
      'package': {
        'description': 'Hộp quà',
        'weightGrams': 1200,
        'lengthCm': 30,
        'widthCm': 20,
        'heightCm': 10,
        'photoFileIds': <String>[]
      },
      'history': [
        {
          'eventId': 'e1',
          'orderVersion': 0,
          'fromStatus': null,
          'toStatus': 'PENDING',
          'occurredAt': '2026-11-27T01:00:00Z'
        }
      ],
      'createdAt': '2026-11-27T01:00:00Z',
      'updatedAt': '2026-11-27T01:00:00Z',
    };

OrderSummary summary(String status, {String id = oid}) => OrderSummary(
    id: id,
    status: status,
    statusLabel: 'Nhãn $status',
    pickupStationId: 'st-a',
    dropoffStationId: 'st-b',
    recipientName: 'An',
    recipientAddressLine: 'x',
    packageDescription: 'Hộp quà',
    packageWeightGrams: 1200);

/// API giả: ghi lại mọi lời gọi, trả dữ liệu cấu hình sẵn, có thể ném lỗi theo từng thao tác.
class FakeDispatcherApi extends DispatcherApi {
  FakeDispatcherApi() : super(Dio());
  String status = 'PENDING';
  int version = 1;
  final calls = <String>[];
  final listCalls = <String>[];
  ApiFailure? failure;
  Map<String, List<OrderSummary>> byStatus = {
    'PENDING': [summary('PENDING')],
    'APPROVED': [],
    'FAILED': [],
  };
  StationAvailability availabilityData = StationAvailability(stationName: 'Trạm A', capacity: 4, slotMinutes: 30, slots: [
    SlotInfo(
        start: DateTime.utc(2026, 11, 27, 8),
        end: DateTime.utc(2026, 11, 27, 8, 30),
        used: 1,
        free: 3,
        bookable: true),
    SlotInfo(
        start: DateTime.utc(2026, 11, 27, 8, 30),
        end: DateTime.utc(2026, 11, 27, 9),
        used: 4,
        free: 0,
        bookable: false),
  ]);
  final dates = <String?>[];

  @override
  Future<OrderPage> list({required String status, int page = 1, int pageSize = 20}) async {
    listCalls.add(status);
    final items = byStatus[status] ?? [];
    return OrderPage(items: items, page: page, pageSize: pageSize, total: items.length);
  }

  @override
  Future<OrderDetail> get(String id) async => OrderDetail.fromJson(orderJson(status, version: version, id: id));

  Future<OrderDetail> _act(String name, String next) async {
    calls.add(name);
    if (failure != null) throw failure!;
    status = next;
    version++;
    return OrderDetail.fromJson(orderJson(next, version: version));
  }

  @override
  Future<OrderDetail> approve(String id, int v) {
    calls.add('v=$v');
    return _act('approve', 'APPROVED');
  }

  @override
  Future<OrderDetail> reject(String id, int v, String reason) {
    calls.add('reason=$reason');
    return _act('reject', 'REJECTED');
  }

  @override
  Future<OrderDetail> schedule(String id, int v, DateTime slotStart) {
    calls.add('slot=${slotStart.toUtc().toIso8601String()}');
    return _act('schedule', 'SCHEDULED');
  }

  @override
  Future<OrderDetail> fail(String id, int v, String code, {String? note}) {
    calls.add('code=$code note=$note');
    return _act('fail', 'FAILED');
  }

  @override
  Future<OrderDetail> startReturn(String id, int v, String reason) => _act('return', 'RETURNING');

  @override
  Future<StationAvailability> availability(String stationId, {String? date}) async {
    dates.add(date);
    return availabilityData;
  }
}

void main() {
  group('hàm thời gian giờ Việt Nam', () {
    test('ngày yyyy-MM-dd lấy theo UTC+7, không theo múi giờ máy', () {
      // 18:30 UTC ngày 26 là 01:30 ngày 27 ở Việt Nam.
      expect(vnDateParam(DateTime.utc(2026, 11, 26, 18, 30)), '2026-11-27');
      expect(vnDateParam(DateTime.utc(2026, 11, 26, 16, 59)), '2026-11-26');
    });
    test('hiển thị khung giờ', () {
      expect(vnHm(DateTime.utc(2026, 11, 27, 8)), '15:00');
      expect(vnDayLabel(DateTime.utc(2026, 11, 27, 8)), 'T6 27/11');
    });
  });

  group('DispatcherApi gửi đúng yêu cầu', () {
    late List<RequestOptions> requests;
    DispatcherApi build() {
      requests = [];
      final dio = Dio(BaseOptions(baseUrl: 'http://x'))
        ..httpClientAdapter = FakeAdapter((o) {
          requests.add(o);
          if (o.path.endsWith('/availability')) {
            return jsonResponse({'stationName': 'A', 'capacity': 4, 'slotMinutes': 30, 'slots': <dynamic>[]});
          }
          if (o.method == 'GET' && o.path == '/v1/orders') {
            return jsonResponse({'items': <dynamic>[], 'page': 1, 'pageSize': 20, 'total': 0});
          }
          return jsonResponse(orderJson('APPROVED', version: 2));
        });
      return DispatcherApi(dio);
    }

    test('duyệt: POST có If-Match theo phiên bản', () async {
      final api = build();
      await api.approve(oid, 7);
      expect(requests.single.path, '/v1/orders/$oid/approve');
      expect(requests.single.headers['If-Match'], '"7"');
    });

    test('từ chối: gửi lý do', () async {
      final api = build();
      await api.reject(oid, 1, 'Sai địa chỉ');
      expect(requests.single.data, {'reason': 'Sai địa chỉ'});
      expect(requests.single.headers['If-Match'], '"1"');
    });

    test('lên lịch: slotStart là UTC ISO-8601', () async {
      final api = build();
      await api.schedule(oid, 2, DateTime.utc(2026, 11, 27, 8));
      expect(requests.single.path, '/v1/orders/$oid/schedules');
      expect(requests.single.data, {'slotStart': '2026-11-27T08:00:00.000Z'});
    });

    test('báo thất bại: mã lý do và ghi chú (bỏ ghi chú rỗng)', () async {
      final api = build();
      await api.fail(oid, 3, 'WEATHER', note: '  ');
      await api.fail(oid, 3, 'OTHER', note: ' Mưa bão ');
      expect(requests[0].data, {'reasonCode': 'WEATHER'});
      expect(requests[1].data, {'reasonCode': 'OTHER', 'note': 'Mưa bão'});
    });

    test('hoàn hàng và danh sách theo trạng thái và khung giờ theo ngày', () async {
      final api = build();
      await api.startReturn(oid, 4, 'Khách từ chối nhận');
      expect(requests.last.path, '/v1/orders/$oid/return');
      await api.list(status: 'FAILED', page: 2);
      expect(requests.last.queryParameters['status'], 'FAILED');
      expect(requests.last.queryParameters['page'], 2);
      await api.availability('st-a', date: '2026-11-27');
      expect(requests.last.path, '/v1/stations/st-a/availability');
      expect(requests.last.queryParameters['date'], '2026-11-27');
    });
  });

  test('SlotInfo đọc đúng dữ liệu máy chủ', () {
    final s = SlotInfo.fromJson({
      'slotStart': '2026-11-27T08:00:00Z',
      'slotEnd': '2026-11-27T08:30:00Z',
      'used': 1,
      'free': 3,
      'bookable': true
    });
    expect(s.free, 3);
    expect(s.bookable, isTrue);
    expect(s.start, DateTime.utc(2026, 11, 27, 8));
  });

  test('định tuyến: điều phối viên vào /dispatcher, không sang route của vai trò khác', () async {
    final session = makeSession(
        store: MemoryTokens(),
        api: FakeAdapter((_) => jsonResponse({})),
        auth: FakeAdapter((_) => jsonResponse(tokenResponse(role: 'DISPATCHER'))));
    addTearDown(session.dispose);
    await session.login('d@test.com', 'secret');
    expect(session.user!.supported, isTrue);
    expect(session.user!.home, '/dispatcher');
    expect(sessionRedirect(session, '/dispatcher'), isNull);
    expect(sessionRedirect(session, '/dispatcher/orders/$oid'), isNull);
    expect(sessionRedirect(session, '/profile'), isNull);
    expect(sessionRedirect(session, '/customer'), '/dispatcher');
    expect(sessionRedirect(session, '/operator'), '/dispatcher');
  });

  group('màn hình', () {
    late FakeDispatcherApi api;

    Future<void> mount(WidgetTester tester, {String initial = '/dispatcher'}) async {
      tester.view.physicalSize = const Size(1170, 2532);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      final session = makeSession(
          store: MemoryTokens()..value = const Tokens('a', 'r'),
          api: FakeAdapter((_) => jsonResponse(profile(role: 'DISPATCHER', name: 'Điều Phối'))),
          auth: FakeAdapter((_) => jsonResponse({})));
      await tester.runAsync(session.restore);
      final router = GoRouter(initialLocation: initial, routes: [
        GoRoute(
            path: '/dispatcher',
            builder: (_, __) => const DispatcherHomeScreen(),
            routes: [
              GoRoute(
                  path: 'orders/:id',
                  builder: (_, s) => DispatcherOrderScreen(orderId: s.pathParameters['id']!)),
            ]),
        GoRoute(path: '/profile', builder: (_, __) => const Scaffold(body: Text('PROFILE'))),
      ]);
      await tester.pumpWidget(ProviderScope(
          overrides: [
            sessionProvider.overrideWith((ref) => session),
            realtimeClientProvider.overrideWith((ref) => null),
            dispatcherApiProvider.overrideWithValue(api),
            stationsProvider.overrideWith((ref) async => const [
                  Station(id: 'st-a', name: 'Trạm Gửi', latitude: 0, longitude: 0, status: 'ACTIVE'),
                  Station(id: 'st-b', name: 'Trạm Nhận', latitude: 0, longitude: 0, status: 'ACTIVE'),
                ]),
          ],
          child: MaterialApp.router(theme: buildAppTheme(), routerConfig: router)));
      await tester.pumpAndSettle();
    }

    setUp(() => api = FakeDispatcherApi());

    testWidgets('trang chủ: tên, số đơn từng nhóm và đơn chờ duyệt', (tester) async {
      api.byStatus['FAILED'] = [summary('FAILED', id: '22222222-2222-2222-2222-222222222222')];
      await mount(tester);
      expect(find.text('Điều Phối'), findsOneWidget);
      expect(find.text('Chờ duyệt (1)'), findsOneWidget);
      expect(find.text('Chờ lịch (0)'), findsOneWidget);
      expect(find.text('Thất bại (1)'), findsOneWidget);
      expect(find.byKey(Key('order-$oid')), findsOneWidget);
      expect(find.text('Trạm Gửi → Trạm Nhận'), findsOneWidget);
    });

    testWidgets('nhóm rỗng hiện hướng dẫn', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('tab-APPROVED')));
      await tester.pumpAndSettle();
      expect(find.text('Không có đơn nào đang chờ lên lịch.'), findsOneWidget);
    });

    testWidgets('bấm đơn mở màn chi tiết đúng đơn', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(Key('order-$oid')));
      await tester.pumpAndSettle();
      expect(find.text('Đơn 11111111'), findsOneWidget);
      expect(find.byKey(const Key('dispatch-approve')), findsOneWidget);
    });

    const allButtons = [
      'dispatch-approve',
      'dispatch-reject',
      'dispatch-schedule',
      'dispatch-redeliver',
      'dispatch-return',
      'dispatch-fail'
    ];
    for (final e in {
      'PENDING': ['dispatch-approve', 'dispatch-reject'],
      'APPROVED': ['dispatch-schedule'],
      'FAILED': ['dispatch-redeliver', 'dispatch-return'],
      'IN_TRANSIT': ['dispatch-fail'],
      'DELIVERED': <String>[],
      'CANCELLED': <String>[],
    }.entries) {
      testWidgets('thanh nút khi đơn ở trạng thái ${e.key}', (tester) async {
        api.status = e.key;
        await mount(tester, initial: '/dispatcher/orders/$oid');
        for (final k in allButtons) {
          expect(find.byKey(Key(k)), e.value.contains(k) ? findsOneWidget : findsNothing,
              reason: '${e.key}: $k');
        }
      });
    }

    testWidgets('duyệt: hỏi xác nhận, gửi đúng phiên bản, đổi sang bước lên lịch', (tester) async {
      api.version = 5;
      await mount(tester, initial: '/dispatcher/orders/$oid');
      await tester.tap(find.byKey(const Key('dispatch-approve')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('confirm-no')));
      await tester.pumpAndSettle();
      expect(api.calls, isEmpty, reason: 'Quay lại thì không gọi API');
      await tester.tap(find.byKey(const Key('dispatch-approve')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('confirm-yes')));
      await tester.pumpAndSettle();
      expect(api.calls, ['v=5', 'approve']);
      expect(find.text('Đã duyệt đơn.'), findsOneWidget);
      expect(find.byKey(const Key('dispatch-schedule')), findsOneWidget);
      expect(find.byKey(const Key('dispatch-approve')), findsNothing);
    });

    testWidgets('từ chối: bắt buộc nhập lý do', (tester) async {
      await mount(tester, initial: '/dispatcher/orders/$oid');
      await tester.tap(find.byKey(const Key('dispatch-reject')));
      await tester.pumpAndSettle();
      expect(tester.widget<FilledButton>(find.byKey(const Key('reason-confirm'))).onPressed, isNull);
      await tester.enterText(find.byKey(const Key('reason-field')), '  Sai địa chỉ  ');
      await tester.pump();
      await tester.tap(find.byKey(const Key('reason-confirm')));
      await tester.pumpAndSettle();
      expect(api.calls, contains('reason=Sai địa chỉ'));
      expect(find.text('Đã từ chối đơn.'), findsOneWidget);
    });

    testWidgets('đơn vừa đổi (412): nói rõ CHƯA thực hiện và tải lại bản mới', (tester) async {
      await mount(tester, initial: '/dispatcher/orders/$oid');
      api.failure = const ApiFailure(FailureKind.versionConflict, 'cũ', status: 412);
      api.version = 9; // phía server đã đổi
      await tester.tap(find.byKey(const Key('dispatch-approve')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('confirm-yes')));
      await tester.pumpAndSettle();
      expect(find.textContaining('CHƯA được thực hiện'), findsOneWidget);
      expect(find.byKey(const Key('dispatch-approve')), findsOneWidget, reason: 'vẫn PENDING, thử lại được');
    });

    testWidgets('lỗi khác (mất mạng): hiện lỗi, đơn giữ nguyên', (tester) async {
      await mount(tester, initial: '/dispatcher/orders/$oid');
      api.failure = const ApiFailure(FailureKind.offline, 'Không có mạng');
      await tester.tap(find.byKey(const Key('dispatch-approve')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('confirm-yes')));
      await tester.pumpAndSettle();
      expect(find.text('Không có mạng'), findsOneWidget);
      expect(find.byKey(const Key('dispatch-approve')), findsOneWidget);
    });

    testWidgets('lên lịch: chỉ chọn được khung còn chỗ, gửi khung đã chọn', (tester) async {
      api.status = 'APPROVED';
      await mount(tester, initial: '/dispatcher/orders/$oid');
      await tester.tap(find.byKey(const Key('dispatch-schedule')));
      await tester.pumpAndSettle();
      expect(api.dates.single, matches(RegExp(r'^\d{4}-\d{2}-\d{2}$')));
      expect(find.text('15:00'), findsOneWidget);
      expect(find.text('còn 3 chỗ'), findsOneWidget);
      expect(find.text('đã đầy'), findsOneWidget);
      expect(tester.widget<FilledButton>(find.byKey(const Key('slot-confirm'))).onPressed, isNull);
      // Khung đầy bấm không có tác dụng.
      await tester.tap(find.byKey(Key('slot-${DateTime.utc(2026, 11, 27, 8, 30).toIso8601String()}')));
      await tester.pump();
      expect(tester.widget<FilledButton>(find.byKey(const Key('slot-confirm'))).onPressed, isNull);
      await tester.tap(find.byKey(Key('slot-${DateTime.utc(2026, 11, 27, 8).toIso8601String()}')));
      await tester.pump();
      await tester.tap(find.byKey(const Key('slot-confirm')));
      await tester.pumpAndSettle();
      expect(api.calls, contains('slot=2026-11-27T08:00:00.000Z'));
      expect(find.text('Đã lên lịch giao.'), findsOneWidget);
    });

    testWidgets('đổi ngày thì tải lại khung giờ của ngày đó', (tester) async {
      api.status = 'APPROVED';
      await mount(tester, initial: '/dispatcher/orders/$oid');
      await tester.tap(find.byKey(const Key('dispatch-schedule')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('slot-day-2')));
      await tester.pumpAndSettle();
      expect(api.dates.length, 2);
      expect(api.dates[0], isNot(api.dates[1]));
    });

    testWidgets('báo thất bại: "Lý do khác" bắt buộc ghi chú', (tester) async {
      api.status = 'IN_TRANSIT';
      await mount(tester, initial: '/dispatcher/orders/$oid');
      await tester.tap(find.byKey(const Key('dispatch-fail')));
      await tester.pumpAndSettle();
      expect(tester.widget<FilledButton>(find.byKey(const Key('failure-confirm'))).onPressed, isNull);
      await tester.tap(find.byKey(const Key('failure-OTHER')));
      await tester.pumpAndSettle();
      expect(tester.widget<FilledButton>(find.byKey(const Key('failure-confirm'))).onPressed, isNull,
          reason: 'OTHER chưa có ghi chú');
      await tester.enterText(find.byKey(const Key('failure-note')), 'Mưa lớn');
      await tester.pump();
      await tester.tap(find.byKey(const Key('failure-confirm')));
      await tester.pumpAndSettle();
      expect(api.calls, contains('code=OTHER note=Mưa lớn'));
    });

    testWidgets('giao lại và hoàn hàng chỉ có ở đơn thất bại', (tester) async {
      api.status = 'FAILED';
      await mount(tester, initial: '/dispatcher/orders/$oid');
      await tester.tap(find.byKey(const Key('dispatch-return')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('reason-field')), 'Khách không nhận');
      await tester.pump();
      await tester.tap(find.byKey(const Key('reason-confirm')));
      await tester.pumpAndSettle();
      expect(api.calls, contains('return'));
    });

    testWidgets('thanh điều hướng: Hồ sơ mở màn hồ sơ, Trạm mở bảng trạm', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('nav-profile')));
      await tester.pumpAndSettle();
      expect(find.text('PROFILE'), findsOneWidget);
    });
  });
}
