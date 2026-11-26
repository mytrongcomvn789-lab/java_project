import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/offline/pending_action.dart';
import 'package:smartdrone_mobile/features/operator/operator_api.dart';
import 'package:smartdrone_mobile/features/operator/operator_controllers.dart';
import 'package:smartdrone_mobile/features/operator/operator_models.dart';
import 'package:smartdrone_mobile/features/operator/operator_screens.dart';
import 'package:smartdrone_mobile/features/operator/operator_sync.dart';
import 'package:smartdrone_mobile/features/operator/return_controller.dart';
import 'package:smartdrone_mobile/features/operator/sync_widgets.dart';
import 'package:smartdrone_mobile/features/orders/customer_order_models.dart';
import 'package:smartdrone_mobile/features/orders/order_detail_controller.dart';

import 'support/pending_fakes.dart';

const oid = '22222222-2222-2222-2222-222222222222';
const mine = 'st-mine';
const other = 'st-other';

DioException offline() =>
    DioException.connectionError(requestOptions: RequestOptions(), reason: 'off');
DioException timeout() => DioException(
    requestOptions: RequestOptions(), type: DioExceptionType.receiveTimeout);
DioException http(int status, String code, [String message = 'msg']) => DioException(
    requestOptions: RequestOptions(path: '/x'),
    response: Response(
        requestOptions: RequestOptions(path: '/x'),
        statusCode: status,
        data: {'code': code, 'message': message}));

OrderDetail order(String status, int version,
        {List<Map<String, dynamic>> history = const []}) =>
    OrderDetail.fromJson({
      'id': oid,
      'version': version,
      'status': status,
      'statusLabel': status,
      'pickupStationId': mine,
      'dropoffStationId': other,
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
        'photoFileIds': []
      },
      'history': history,
    });

OrderSummary summary(int i, {required String pickup}) => OrderSummary(
    id: '${i.toString().padLeft(8, '0')}-0000-0000-0000-000000000000',
    status: 'RETURNING',
    statusLabel: 'Đang hoàn hàng',
    pickupStationId: pickup,
    dropoffStationId: other,
    recipientName: 'Người $i',
    recipientAddressLine: 'Địa chỉ $i');

/// Server giả: một đơn với trạng thái và phiên bản chỉnh được, cùng các lỗi cho từng thao tác.
class FakeSyncApi extends OperatorApi {
  FakeSyncApi() : super(Dio());
  String status = 'SCHEDULED';
  int version = 2;
  List<Map<String, dynamic>> history = [];
  Object? detailError, receiveError, confirmError, returnError;
  final received = <ReceiveRequest>[];
  final confirmed = <ConfirmRequest>[];
  final returned = <CompleteReturnRequest>[];
  List<OrderSummary> returning = [];
  List<AssignedStation> stations = const [
    AssignedStation(id: mine, name: 'Trạm Của Tôi', status: 'ACTIVE')
  ];

  Never _throw(Object e) => throw e;

  @override
  Future<List<AssignedStation>> assignedStations() async => stations;

  @override
  Future<OrderPage> listReturning({int page = 1, int pageSize = 20, String? stationId}) async =>
      OrderPage(items: returning, page: 1, pageSize: pageSize, total: returning.length);

  @override
  Future<OrderPage> listReceipts({int page = 1, int pageSize = 20, String? stationId}) async =>
      OrderPage(items: const [], page: 1, pageSize: pageSize, total: 0);

  @override
  Future<OrderPage> listAwaitingPickup({int page = 1, int pageSize = 20, String? stationId}) async =>
      OrderPage(items: const [], page: 1, pageSize: pageSize, total: 0);

  @override
  Future<OrderDetail> orderDetail(String id) async {
    final e = detailError;
    if (e != null) _throw(e);
    return order(status, version, history: history);
  }

  @override
  Future<OrderDetail> receive(ReceiveRequest r) async {
    received.add(r);
    final e = receiveError;
    if (e != null) {
      receiveError = null;
      _throw(e);
    }
    status = 'PREPARING';
    version++;
    return order(status, version);
  }

  @override
  Future<ConfirmResult> confirm(ConfirmRequest r) async {
    confirmed.add(r);
    final e = confirmError;
    if (e != null) {
      confirmError = null;
      _throw(e);
    }
    return ConfirmResult(
        DeliveryConfirmation(
            id: 'c1',
            orderId: r.orderId,
            orderVersion: r.version + 1,
            method: 'OTP',
            confirmedBy: 'op-1',
            stationId: mine,
            photoFileId: r.photoFileId),
        replayed: false);
  }

  @override
  Future<OrderDetail> completeReturn(CompleteReturnRequest r) async {
    returned.add(r);
    final e = returnError;
    if (e != null) {
      returnError = null;
      _throw(e);
    }
    status = 'RETURNED';
    version++;
    return order(status, version);
  }
}

PendingAction receiveAction({int version = 2}) => pendingReceive(
    'op-1',
    ReceiveRequest(
        orderId: oid, version: version, attemptId: 'att-1', packageId: 'pkg-1'));

PendingAction confirmAction({int version = 5, String user = 'op-1'}) => pendingConfirm(
    user,
    ConfirmRequest(
        orderId: oid,
        version: version,
        photoFileId: 'photo-1',
        credential: PickupCredential.otp('123456'),
        note: 'ghi chú'));

PendingAction returnAction({int version = 7}) =>
    pendingReturn('op-1', CompleteReturnRequest(orderId: oid, version: version));

Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 10));

void main() {
  late FakeSyncApi api;
  late MemoryPendingStore store;
  late StateProvider<String?> user;
  late ProviderContainer container;

  ProviderContainer makeContainer() => ProviderContainer(overrides: [
        ...pendingOverrides(store),
        operatorApiProvider.overrideWithValue(api),
        operatorUserProvider.overrideWith((ref) => ref.watch(user)),
      ]);

  setUp(() {
    api = FakeSyncApi();
    store = MemoryPendingStore();
    user = StateProvider<String?>((ref) => 'op-1');
    container = makeContainer();
    addTearDown(container.dispose);
  });

  SyncState read() => container.read(operatorSyncProvider);
  OperatorSyncController ctl() => container.read(operatorSyncProvider.notifier);

  Future<void> boot() async {
    container.listen(operatorSyncProvider, (_, __) {});
    await settle();
  }

  group('PendingAction', () {
    test('đi và về JSON giữ nguyên; toString che dữ liệu nhạy cảm', () {
      final a = confirmAction();
      final b = PendingAction.tryFromJson(a.toJson())!;
      expect(b.id, a.id);
      expect(b.kind, PendingKind.confirm);
      expect(b.payload['otp'], '123456');
      expect(a.toString(), isNot(contains('123456')));
    });

    test('bản ghi hỏng bị bỏ thay vì làm sập app', () {
      expect(PendingAction.tryFromJson({'id': 'x'}), isNull);
      expect(PendingAction.tryFromJson('rác'), isNull);
      expect(PendingAction.tryFromJson({...receiveAction().toJson(), 'kind': 'lạ'}), isNull);
    });

    test('request đi qua hàng đợi và về lại đúng từng trường', () {
      final c = confirmOf(confirmAction());
      expect(c.version, 5);
      expect(c.photoFileId, 'photo-1');
      expect(c.credential.kind, CredentialKind.otp);
      expect(c.note, 'ghi chú');
      final qr = '55555555-5555-5555-5555-555555555555.${'A' * 43}';
      final q = confirmOf(pendingConfirm(
          'op-1',
          ConfirmRequest(
              orderId: oid,
              version: 1,
              photoFileId: 'p',
              credential: PickupCredential.qr(qr))));
      expect(q.credential.kind, CredentialKind.qr);
      expect(q.credential.toBody(), {'qrToken': qr});
      final r = receiveOf(receiveAction());
      expect([r.attemptId, r.packageId, r.version], ['att-1', 'pkg-1', 2]);
    });

    test('thời gian chờ tăng gấp đôi và dừng ở 5 phút', () {
      expect(syncRetryDelay(0), const Duration(seconds: 15));
      expect(syncRetryDelay(1), const Duration(seconds: 30));
      expect(syncRetryDelay(3), const Duration(seconds: 120));
      expect(syncRetryDelay(10), const Duration(minutes: 5));
    });
  });

  group('hàng đợi', () {
    test('đọc lại thao tác đã lưu khi mở app và tự gửi', () async {
      api.status = 'SCHEDULED';
      store.data['op-1'] = [receiveAction()];
      await boot();
      await settle();
      expect(api.received.length, 1);
      expect(read().isEmpty, isTrue);
      expect(store.data['op-1'], isEmpty);
    });

    test('không thấy việc của tài khoản khác; đổi tài khoản nạp hàng đợi của người mới', () async {
      store.data['op-2'] = [confirmAction(user: 'op-2')];
      await boot();
      expect(read().isEmpty, isTrue);
      container.read(user.notifier).state = 'op-2';
      api.confirmError = offline();
      await settle();
      await settle();
      expect(read().actions.single.userId, 'op-2');
    });

    test('đăng xuất thì không xếp thêm được', () async {
      await boot();
      container.read(user.notifier).state = null;
      await settle();
      expect(await ctl().enqueue(receiveAction()), isFalse);
    });

    test('xếp trùng cùng đơn và loại chỉ giữ một bản', () async {
      await boot();
      api.detailError = offline();
      expect(await ctl().enqueue(receiveAction()), isTrue);
      expect(await ctl().enqueue(receiveAction()), isTrue);
      expect(read().actions.length, 1);
    });

    test('không ghi được kho thì enqueue trả false và không để lại gì', () async {
      await boot();
      store.failWrites = true;
      expect(await ctl().enqueue(receiveAction()), isFalse);
      expect(read().isEmpty, isTrue);
    });

    test('bỏ yêu cầu xóa khỏi hàng đợi và kho', () async {
      await boot();
      api.detailError = http(404, 'RESOURCE_NOT_FOUND');
      await ctl().enqueue(receiveAction());
      await ctl().syncNow();
      expect(read().needsAttention, 1);
      await ctl().discard(read().actions.single.id);
      expect(read().isEmpty, isTrue);
      expect(store.data['op-1'], isEmpty);
    });
  });

  group('đồng bộ nhận kiện (không replay được)', () {
    test('đơn còn chờ nhận đúng phiên bản: gửi và dọn hàng đợi', () async {
      await boot();
      await ctl().enqueue(receiveAction());
      await ctl().syncNow();
      expect(api.received.single.version, 2);
      expect(read().isEmpty, isTrue);
    });

    test('mất mạng khi tải đơn: giữ nguyên, tăng số lần thử, không gửi gì', () async {
      await boot();
      api.detailError = offline();
      await ctl().enqueue(receiveAction());
      await ctl().syncNow();
      expect(api.received, isEmpty);
      expect(read().waiting, 1);
      expect(read().actions.single.attempts, 1);
      api.detailError = null; // có mạng lại
      await ctl().syncNow();
      expect(api.received.length, 1);
      expect(read().isEmpty, isTrue);
    });

    test('mất mạng khi gửi: giữ lại và lần sau kiểm tra lại đơn trước khi gửi', () async {
      await boot();
      api.receiveError = timeout();
      await ctl().enqueue(receiveAction());
      await ctl().syncNow();
      expect(api.received.length, 1);
      expect(read().waiting, 1);
      // Thực ra lần vừa rồi đã tới server: đơn đã PREPARING và lịch sử ghi lại.
      api.status = 'PREPARING';
      api.version = 3;
      api.history = [
        {'eventId': 'e', 'orderVersion': 3, 'fromStatus': 'SCHEDULED', 'toStatus': 'PREPARING'}
      ];
      await ctl().syncNow();
      expect(api.received.length, 1); // không gửi lần hai
      expect(read().isEmpty, isTrue);
    });

    test('phiên bản đã đổi: không gửi, báo người dùng xem lại', () async {
      await boot();
      api.version = 4;
      await ctl().enqueue(receiveAction());
      await ctl().syncNow();
      expect(api.received, isEmpty);
      expect(read().needsAttention, 1);
      expect(read().actions.single.message, contains('Đơn đã thay đổi'));
      await ctl().syncNow(); // việc cần xem lại không tự gửi nữa
      expect(api.received, isEmpty);
    });

    test('đơn đã sang trạng thái khác (ví dụ bị hủy): không gửi, báo rõ trạng thái', () async {
      await boot();
      api.status = 'CANCELLED';
      await ctl().enqueue(receiveAction());
      await ctl().syncNow();
      expect(api.received, isEmpty);
      expect(read().actions.single.status, PendingStatus.needsAttention);
      expect(read().actions.single.message, contains('CANCELLED'));
    });

    test('server từ chối (404 ngoài phạm vi): cần xem lại, không thử mãi', () async {
      await boot();
      api.receiveError = http(404, 'RESOURCE_NOT_FOUND', 'Không có quyền');
      await ctl().enqueue(receiveAction());
      await ctl().syncNow();
      expect(read().needsAttention, 1);
      expect(read().actions.single.message, 'Không có quyền');
    });
  });

  group('đồng bộ xác nhận giao (replay được)', () {
    test('gửi lại ĐÚNG yêu cầu đã lưu rồi dọn hàng đợi', () async {
      await boot();
      await ctl().enqueue(confirmAction());
      await ctl().syncNow();
      final r = api.confirmed.single;
      expect([r.version, r.photoFileId, r.credential.value, r.note],
          [5, 'photo-1', '123456', 'ghi chú']);
      expect(read().isEmpty, isTrue);
    });

    test('mất mạng: giữ nguyên rồi gửi lại y hệt khi có mạng', () async {
      await boot();
      api.confirmError = offline();
      await ctl().enqueue(confirmAction());
      await ctl().syncNow();
      expect(read().waiting, 1);
      await ctl().syncNow();
      expect(api.confirmed.length, 2);
      expect(api.confirmed[1].photoFileId, api.confirmed[0].photoFileId);
      expect(api.confirmed[1].credential.value, api.confirmed[0].credential.value);
      expect(read().isEmpty, isTrue);
    });

    test('mã hết hạn (410): cần xem lại, hướng dẫn nhờ khách mở mã mới', () async {
      await boot();
      api.confirmError = http(410, 'PICKUP_CODE_EXPIRED');
      await ctl().enqueue(confirmAction());
      await ctl().syncNow();
      expect(read().needsAttention, 1);
      expect(read().actions.single.message, contains('mã mới'));
    });

    test('bị đổi phiên bản nhưng đơn thực ra đã giao: coi như xong', () async {
      await boot();
      api.confirmError = http(412, 'VERSION_CONFLICT');
      api.status = 'DELIVERED';
      await ctl().enqueue(confirmAction());
      await ctl().syncNow();
      expect(read().isEmpty, isTrue);
    });

    test('bị đổi phiên bản và đơn chưa giao: cần xem lại', () async {
      await boot();
      api.confirmError = http(412, 'VERSION_CONFLICT');
      api.status = 'AWAITING_PICKUP';
      await ctl().enqueue(confirmAction());
      await ctl().syncNow();
      expect(read().needsAttention, 1);
    });

    test('quá nhiều yêu cầu (429) là lỗi tạm thời, mã bị khóa thì không', () async {
      await boot();
      api.confirmError = http(429, 'RATE_LIMITED');
      await ctl().enqueue(confirmAction());
      await ctl().syncNow();
      expect(read().waiting, 1);
      api.confirmError = http(429, 'PICKUP_CODE_LOCKED');
      await ctl().syncNow();
      expect(read().needsAttention, 1);
    });
  });

  group('đồng bộ nhận hàng hoàn', () {
    test('đơn RETURNING đúng phiên bản: gửi, kèm ghi chú nếu có', () async {
      await boot();
      api.status = 'RETURNING';
      api.version = 7;
      await ctl().enqueue(returnAction());
      await ctl().syncNow();
      expect(api.returned.single.version, 7);
      expect(read().isEmpty, isTrue);
    });

    test('đã RETURNED theo lịch sử: không gửi lại', () async {
      await boot();
      api.status = 'RETURNED';
      api.version = 8;
      api.history = [
        {'eventId': 'e', 'orderVersion': 8, 'fromStatus': 'RETURNING', 'toStatus': 'RETURNED'}
      ];
      await ctl().enqueue(returnAction());
      await ctl().syncNow();
      expect(api.returned, isEmpty);
      expect(read().isEmpty, isTrue);
    });

    test('một việc mất mạng thì dừng cả đợt, giữ thứ tự', () async {
      await boot();
      api.detailError = offline();
      await ctl().enqueue(returnAction());
      await ctl().enqueue(confirmAction());
      await ctl().syncNow();
      expect(api.confirmed, isEmpty); // việc sau không chạy khi việc trước còn mất mạng
      expect(read().waiting, 2);
    });
  });

  group('tab "Chờ nhận hoàn"', () {
    test('chỉ giữ đơn có trạm GỬI thuộc phạm vi của operator', () async {
      api.returning = [
        summary(1, pickup: mine),
        summary(2, pickup: other),
      ];
      container.listen(returnListProvider, (_, __) {});
      await settle();
      final s = container.read(returnListProvider);
      expect(s.items.map((e) => e.recipientName), ['Người 1']);
      expect(s.total, 1);
    });
  });

  group('xác nhận nhận hàng hoàn', () {
    ReturnDetailState rd() => container.read(returnDetailProvider(oid));
    ReturnDetailController rc() => container.read(returnDetailProvider(oid).notifier);
    Future<void> bootReturn() async {
      api.status = 'RETURNING';
      api.version = 7;
      api.history = [
        {
          'eventId': 'e1',
          'orderVersion': 5,
          'fromStatus': 'DELIVERING',
          'toStatus': 'FAILED',
          'reason': 'Khách vắng nhà'
        },
        {
          'eventId': 'e2',
          'orderVersion': 7,
          'fromStatus': 'FAILED',
          'toStatus': 'RETURNING',
          'reason': 'Hết số lần giao'
        },
      ];
      container.listen(returnDetailProvider(oid), (_, __) {});
      await settle();
    }

    test('hiện các lần thất bại, mới nhất trước', () async {
      await bootReturn();
      expect(rd().failures.map((e) => e.reason), ['Hết số lần giao', 'Khách vắng nhà']);
      expect(rd().canComplete, isTrue);
    });

    test('thành công: gửi đúng version làm If-Match', () async {
      await bootReturn();
      expect(await rc().complete('đã kiểm tra'), isTrue);
      expect(api.returned.single.version, 7);
      expect(api.returned.single.ifMatch, '"7"');
      expect(api.returned.single.toBody(), {'note': 'đã kiểm tra'});
      expect(rd().done, isTrue);
    });

    test('không có ghi chú thì không gửi body', () {
      expect(const CompleteReturnRequest(orderId: oid, version: 1).toBody(), isNull);
      expect(const CompleteReturnRequest(orderId: oid, version: 1, note: '  ').toBody(), isNull);
    });

    test('mất mạng: xếp hàng, CHƯA hoàn tất, không bấm lần hai', () async {
      await bootReturn();
      api.returnError = offline();
      expect(await rc().complete(null), isFalse);
      expect(rd().queued, isTrue);
      expect(rd().canComplete, isFalse);
      expect(store.data['op-1']!.single.kind, PendingKind.completeReturn);
      expect(await rc().complete(null), isFalse);
      expect(api.returned.length, 1);
      await container.read(operatorSyncProvider.notifier).syncNow();
      expect(api.returned.length, 2);
      expect(container.read(operatorSyncProvider).isEmpty, isTrue);
    });

    test('phiên bản cũ (412): tải lại, báo đơn vừa thay đổi', () async {
      await bootReturn();
      api.returnError = http(412, 'VERSION_CONFLICT');
      expect(await rc().complete(null), isFalse);
      expect(rd().notice, contains('vừa thay đổi'));
      expect(rd().queued, isFalse);
    });

    test('trạm khác (404): không thao tác nữa', () async {
      await bootReturn();
      api.returnError = http(404, 'RESOURCE_NOT_FOUND');
      expect(await rc().complete(null), isFalse);
      expect(rd().gone, isTrue);
      expect(rd().canComplete, isFalse);
    });

    test('đơn không còn RETURNING thì không gọi API', () async {
      await bootReturn();
      api.status = 'RETURNED';
      await rc().reload();
      expect(await rc().complete(null), isFalse);
      expect(api.returned, isEmpty);
    });
  });

  group('màn hình', () {
    Future<ProviderContainer> mount(WidgetTester tester, Widget home) async {
      tester.view.physicalSize = const Size(800, 2600);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final router = GoRouter(routes: [
        GoRoute(path: '/', builder: (_, __) => home),
        GoRoute(path: '/operator', builder: (_, __) => const Scaffold(body: Text('OPERATOR_HOME'))),
        GoRoute(
            path: '/operator/returns/:id',
            builder: (_, s) => ReturnDetailScreen(orderId: s.pathParameters['id']!)),
      ]);
      await tester.pumpWidget(ProviderScope(
          overrides: [
            ...pendingOverrides(store),
            operatorApiProvider.overrideWithValue(api),
            operatorUserProvider.overrideWithValue('op-1'),
            stationsProvider.overrideWith((ref) async => const [
                  Station(id: mine, name: 'Trạm Của Tôi', latitude: 0, longitude: 0, status: 'ACTIVE'),
                  Station(id: other, name: 'Trạm Khác', latitude: 0, longitude: 0, status: 'ACTIVE'),
                ]),
          ],
          child: MaterialApp.router(routerConfig: router)));
      await tester.pumpAndSettle();
      return ProviderScope.containerOf(tester.element(find.byType(MaterialApp)));
    }

    testWidgets('dải đồng bộ: hiện số việc chờ, bấm "Thử lại" thì gửi và biến mất', (tester) async {
    testWidgets('dải đồng bộ: hiện số việc chờ, bấm "Đồng bộ ngay" thì gửi và biến mất', (tester) async {
      store.data['op-1'] = [receiveAction()];
      api.detailError = offline();
      await mount(tester, const Scaffold(body: SyncBanner()));
      expect(find.byKey(const Key('sync-waiting')), findsOneWidget);
      expect(find.textContaining('1 thao tác chờ đồng bộ khi có mạng'), findsOneWidget);
      expect(find.textContaining('1 thao tác đang chờ gửi'), findsOneWidget);
      api.detailError = null;
      await tester.tap(find.byKey(const Key('sync-now')));
      await tester.pumpAndSettle();
      expect(api.received.length, 1);
      expect(find.byKey(const Key('sync-waiting')), findsNothing);
    });

    testWidgets('không có gì chờ thì dải đồng bộ không hiện', (tester) async {
      await mount(tester, const Scaffold(body: SyncBanner()));
      expect(find.byKey(const Key('sync-waiting')), findsNothing);
      expect(find.byType(Container), findsNothing);
    });

    testWidgets('việc bị server từ chối: hiện lý do, "Mở đơn" và "Bỏ yêu cầu"', (tester) async {
      final a = receiveAction().copyWith(
          status: PendingStatus.needsAttention, message: 'Đơn đã thay đổi.');
      store.data['op-1'] = [a];
      await mount(tester, const Scaffold(body: SyncBanner()));
      expect(find.textContaining('Nhận kiện đơn'), findsOneWidget);
      expect(find.text('Đơn đã thay đổi.'), findsOneWidget);
      await tester.tap(find.byKey(Key('sync-discard-${a.id}')));
      await tester.pumpAndSettle();
      expect(find.text('Đơn đã thay đổi.'), findsNothing);
      expect(store.data['op-1'], isEmpty);
    });

    testWidgets('màn nhận hàng hoàn: tích kiểm tra, xác nhận, thành công', (tester) async {
      api.status = 'RETURNING';
      api.version = 7;
      api.history = [
        {'eventId': 'e', 'orderVersion': 5, 'fromStatus': 'DELIVERING', 'toStatus': 'FAILED', 'reason': 'Khách vắng nhà'}
      ];
      await mount(tester, const ReturnDetailScreen(orderId: oid));
      expect(find.textContaining('Khách vắng nhà'), findsOneWidget);
      FilledButton button() =>
          tester.widget<FilledButton>(find.byKey(const Key('return-button')));
      expect(button().onPressed, isNull); // chưa tích
      await tester.tap(find.byKey(const Key('return-checked')));
      await tester.pump();
      await tester.tap(find.byKey(const Key('return-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('return-dismiss')));
      await tester.pumpAndSettle();
      expect(api.returned, isEmpty); // "Quay lại" không gọi API
      await tester.tap(find.byKey(const Key('return-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('return-confirm')));
      await tester.pumpAndSettle();
      expect(api.returned.length, 1);
    });

    testWidgets('màn nhận hàng hoàn mất mạng: "CHƯA hoàn tất", không còn nút', (tester) async {
      api.status = 'RETURNING';
      api.version = 7;
      api.returnError = offline();
      await mount(tester, const ReturnDetailScreen(orderId: oid));
      await tester.tap(find.byKey(const Key('return-checked')));
      await tester.pump();
      await tester.tap(find.byKey(const Key('return-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('return-confirm')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('return-queued')), findsOneWidget);
      expect(find.textContaining('CHƯA được hoàn tất'), findsOneWidget);
      expect(find.byKey(const Key('return-button')), findsNothing);
    });
  });
}
