import 'dart:async';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/core/ui/app_widgets.dart';
import 'package:smartdrone_mobile/features/operator/confirmation_photo_controller.dart';
import 'package:smartdrone_mobile/features/operator/operator_api.dart';
import 'package:smartdrone_mobile/features/operator/operator_controllers.dart';
import 'package:smartdrone_mobile/core/offline/pending_action.dart';
import 'package:smartdrone_mobile/features/operator/operator_models.dart';
import 'package:smartdrone_mobile/features/operator/operator_sync.dart';
import 'package:smartdrone_mobile/features/operator/pickup_confirm_controller.dart';
import 'package:smartdrone_mobile/features/operator/pickup_screens.dart';
import 'package:smartdrone_mobile/features/operator/qr_scanner.dart';
import 'package:smartdrone_mobile/features/orders/customer_order_models.dart';
import 'package:smartdrone_mobile/features/orders/order_detail_controller.dart';
import 'package:smartdrone_mobile/features/orders/photo_picker.dart';

import 'support/pending_fakes.dart';

const orderId = '11111111-1111-1111-1111-111111111111';
final validQr = '55555555-5555-5555-5555-555555555555.${'A' * 43}';

final png = Uint8List.fromList(const [
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
  0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xF8, 0xFF, 0xFF, 0x3F,
  0x00, 0x05, 0xFE, 0x02, 0xFE, 0xA7, 0x35, 0x81, 0x84, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
  0x44, 0xAE, 0x42, 0x60, 0x82
]);

OrderDetail detail(String status, int version) => OrderDetail.fromJson({
      'id': orderId,
      'version': version,
      'status': status,
      'statusLabel': status == 'AWAITING_PICKUP' ? 'Chờ nhận hàng' : 'Đã giao',
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
        'photoFileIds': []
      },
      'history': [],
    });

DioException http(int status, String code, [String message = 'm']) => DioException(
    requestOptions: RequestOptions(path: '/x'),
    response: Response(
        requestOptions: RequestOptions(path: '/x'),
        statusCode: status,
        data: {'code': code, 'message': message}));

DioException offline() =>
    DioException.connectionError(requestOptions: RequestOptions(), reason: 'off');

class FakeConfirmApi extends OperatorApi {
  FakeConfirmApi() : super(Dio());
  String status = 'AWAITING_PICKUP';
  int version = 5;
  final sent = <ConfirmRequest>[];
  Object? confirmError;
  Completer<void>? gate;
  bool replayNext = false;
  Object? detailError;
  int uploadCount = 0;

  @override
  Future<OrderDetail> orderDetail(String id) async {
    final e = detailError;
    if (e != null) throw e;
    return detail(status, version);
  }

  @override
  Future<String> uploadConfirmationPhoto(
          {required Uint8List bytes,
          required String contentType,
          required String filename}) async =>
      'photo-${++uploadCount}';

  @override
  Future<OrderPage> listAwaitingPickup(
          {int page = 1, int pageSize = 20, String? stationId}) async =>
      OrderPage(items: const [], page: 1, pageSize: 50, total: 0);

  @override
  Future<List<AssignedStation>> assignedStations() async => const [];

  @override
  Future<OrderPage> listReceipts(
          {int page = 1, int pageSize = 20, String? stationId}) async =>
      OrderPage(items: const [], page: 1, pageSize: 20, total: 0);

  @override
  Future<OrderPage> listReturning(
          {int page = 1, int pageSize = 20, String? stationId}) async =>
      OrderPage(items: const [], page: 1, pageSize: 50, total: 0);

  @override
  Future<ConfirmResult> confirm(ConfirmRequest r) async {
    sent.add(r);
    final g = gate;
    if (g != null) await g.future;
    final e = confirmError;
    if (e != null) {
      confirmError = null;
      throw e;
    }
    final replay = replayNext;
    replayNext = false;
    return ConfirmResult(
        DeliveryConfirmation(
            id: 'c1',
            orderId: orderId,
            orderVersion: r.version + 1,
            method: r.credential.kind == CredentialKind.otp ? 'OTP' : 'QR',
            confirmedBy: 'u',
            stationId: 's2',
            photoFileId: r.photoFileId),
        replayed: replay);
  }
}

Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 10));

void main() {
  late FakeConfirmApi api;
  late StateProvider<String?> user;
  late ProviderContainer container;

  late MemoryPendingStore store;

  List<Override> overrides() => [
        ...pendingOverrides(store),
        operatorApiProvider.overrideWithValue(api),
        operatorUserProvider.overrideWith((ref) => ref.watch(user)),
        photoPickerProvider.overrideWithValue((s) async =>
            PickedPhoto(name: 'a.png', bytes: png, contentType: 'image/png')),
        stationsProvider.overrideWith((ref) async => const [
              Station(id: 's1', name: 'Trạm Gửi', latitude: 0, longitude: 0, status: 'ACTIVE'),
              Station(id: 's2', name: 'Trạm Nhận', latitude: 0, longitude: 0, status: 'ACTIVE'),
            ]),
      ];

  setUp(() {
    api = FakeConfirmApi();
    store = MemoryPendingStore();
    user = StateProvider<String?>((ref) => 'op-1');
    container = ProviderContainer(overrides: overrides());
    addTearDown(container.dispose);
  });

  PickupConfirmState read() => container.read(pickupConfirmProvider(orderId));
  PickupConfirmController ctl() => container.read(pickupConfirmProvider(orderId).notifier);

  Future<void> boot({bool withPhoto = true}) async {
    container.listen(pickupConfirmProvider(orderId), (_, __) {});
    container.listen(confirmationPhotoProvider(orderId), (_, __) {});
    await settle();
    if (withPhoto) {
      await container.read(confirmationPhotoProvider(orderId).notifier).pick(PhotoSource.camera);
    }
  }

  group('controller', () {
    test('xác nhận bằng OTP: gửi đúng phiên bản, ảnh, mã; chỉ báo giao khi server xác nhận', () async {
      await boot();
      ctl().setCredential(PickupCredential.otp('123456'));
      expect(read().result, isNull);
      expect(await ctl().confirm('  Khách nhận  '), isTrue);
      final r = api.sent.single;
      expect(r.version, 5);
      expect(r.photoFileId, 'photo-1');
      expect(r.credential.kind, CredentialKind.otp);
      expect(r.toBody(), {'otp': '123456', 'photoFileId': 'photo-1', 'note': 'Khách nhận'});
      expect(read().result?.confirmation.method, 'OTP');
      expect(read().awaitingPickup, isFalse);
    });

    test('xác nhận bằng QR chỉ gửi qrToken', () async {
      await boot();
      ctl().setCredential(PickupCredential.qr(validQr));
      expect(await ctl().confirm(null), isTrue);
      expect(api.sent.single.toBody().keys, {'qrToken', 'photoFileId'});
    });

    test('thiếu mã, thiếu ảnh hoặc ảnh chưa tải xong thì không gọi API', () async {
      await boot(withPhoto: false);
      ctl().setCredential(PickupCredential.otp('123456'));
      expect(await ctl().confirm(null), isFalse); // chưa có ảnh
      await container.read(confirmationPhotoProvider(orderId).notifier).pick(PhotoSource.camera);
      ctl().setCredential(null);
      expect(await ctl().confirm(null), isFalse); // chưa có mã
      expect(api.sent, isEmpty);
    });

    test('bấm đúp chỉ gửi một yêu cầu', () async {
      await boot();
      ctl().setCredential(PickupCredential.otp('123456'));
      api.gate = Completer<void>();
      final a = ctl().confirm(null);
      final b = ctl().confirm(null);
      expect(await b, isFalse);
      api.gate!.complete();
      expect(await a, isTrue);
      expect(api.sent.length, 1);
    });

    test('mã sai (422): hiện số lần còn lại, bỏ mã, giữ ảnh, không báo đã giao', () async {
      await boot();
      ctl().setCredential(PickupCredential.otp('000000'));
      api.confirmError = http(422, 'PICKUP_CODE_INVALID', 'Mã nhận hàng không đúng. Còn 4 lần thử');
      expect(await ctl().confirm(null), isFalse);
      expect(read().failure?.kind, FailureKind.pickupCodeInvalid);
      expect(read().failure?.message, contains('Còn 4 lần thử'));
      expect(read().credential, isNull);
      expect(container.read(confirmationPhotoProvider(orderId)).fileId, 'photo-1');
      expect(read().result, isNull);
      expect(read().order?.status, 'AWAITING_PICKUP'); // app không tự đổi trạng thái
    });

    test('mã hết hạn (410), bị khóa (429), đã dùng (409): bỏ mã và báo đúng loại', () async {
      await boot();
      for (final c in [
        (http(410, 'PICKUP_CODE_EXPIRED'), FailureKind.pickupCodeExpired),
        (http(429, 'PICKUP_CODE_LOCKED'), FailureKind.pickupCodeLocked),
        (http(409, 'PICKUP_CODE_USED'), FailureKind.pickupCodeUsed),
      ]) {
        ctl().setCredential(PickupCredential.otp('123456'));
        api.confirmError = c.$1;
        expect(await ctl().confirm(null), isFalse);
        expect(read().failure?.kind, c.$2);
        expect(read().credential, isNull);
      }
    });

    test('phiên bản cũ (412): tải lại, giữ mã và ảnh, gửi lại với phiên bản mới', () async {
      await boot();
      ctl().setCredential(PickupCredential.otp('123456'));
      api.version = 7;
      api.confirmError = http(412, 'VERSION_CONFLICT');
      expect(await ctl().confirm(null), isFalse);
      expect(read().notice, isNotNull);
      expect(read().order?.version, 7);
      expect(read().credential, isNotNull);
      expect(await ctl().confirm(null), isTrue);
      expect(api.sent.map((e) => e.version), [5, 7]);
    });

    test('đơn đã được giao ở nơi khác (409): tải lại và không cho xác nhận nữa', () async {
      await boot();
      ctl().setCredential(PickupCredential.otp('123456'));
      api.status = 'DELIVERED';
      api.confirmError = http(409, 'INVALID_STATE_TRANSITION', 'Đơn đã được giao');
      expect(await ctl().confirm(null), isFalse);
      expect(read().order?.status, 'DELIVERED');
      expect(read().awaitingPickup, isFalse);
    });

    test('mất mạng: không báo thành công, lưu vào hàng đợi; đồng bộ gửi ĐÚNG yêu cầu cũ', () async {
      await boot();
      ctl().setCredential(PickupCredential.otp('123456'));
      api.confirmError = offline();
      expect(await ctl().confirm('ghi chú'), isFalse);
      expect(read().result, isNull);
      expect(read().queued, isTrue);
      expect(read().failure, isNull);
      expect(read().credential, isNull); // mã chỉ còn trong hàng đợi mã hóa
      expect(read().awaitingPickup, isFalse); // không cho xác nhận lần hai khi đã xếp hàng
      final saved = store.data['op-1']!;
      expect(saved.single.kind, PendingKind.confirm);
      expect(saved.single.orderId, orderId);

      await container.read(operatorSyncProvider.notifier).syncNow();
      expect(api.sent.length, 2);
      final a = api.sent[0], b = api.sent[1];
      expect(b.version, a.version);
      expect(b.photoFileId, a.photoFileId);
      expect(b.credential.value, a.credential.value);
      expect(container.read(operatorSyncProvider).isEmpty, isTrue);
      expect(store.data['op-1'], isEmpty);
    });

    test('mất mạng nhưng không lưu được hàng đợi: báo lỗi như cũ, giữ mã để gửi lại', () async {
      await boot();
      ctl().setCredential(PickupCredential.otp('123456'));
      store.failWrites = true;
      api.confirmError = offline();
      expect(await ctl().confirm(null), isFalse);
      expect(read().queued, isFalse);
      expect(read().failure?.kind, FailureKind.offline);
      expect(read().credential, isNotNull);
    });

    test('hết thời gian chờ: gửi lại là an toàn, server trả lại kết quả cũ (replayed)', () async {
      await boot();
      ctl().setCredential(PickupCredential.qr(validQr));
      api.confirmError = DioException(
          requestOptions: RequestOptions(), type: DioExceptionType.receiveTimeout);
      expect(await ctl().confirm(null), isFalse);
      expect(read().queued, isTrue); // chưa rõ kết quả: xếp hàng để gửi lại
      expect(read().result, isNull);
      api.replayNext = true; // lần trước server đã xử lý xong
      await container.read(operatorSyncProvider.notifier).syncNow();
      // Gửi lại y nguyên: server trả lại kết quả cũ nên không giao hai lần và hàng đợi sạch.
      expect(api.sent.length, 2);
      expect(container.read(operatorSyncProvider).isEmpty, isTrue);
    });

    test('404 (sai trạm hoặc ảnh không hợp lệ): thông báo rõ, không treo', () async {
      await boot();
      ctl().setCredential(PickupCredential.otp('123456'));
      api.confirmError = http(404, 'RESOURCE_NOT_FOUND');
      expect(await ctl().confirm(null), isFalse);
      expect(read().failure?.message, contains('trạm nhận'));
      expect(read().submitting, isFalse);
    });

    test('lỗi tải đơn: hiện lỗi; đổi tài khoản bỏ mã đã nhập', () async {
      await boot();
      ctl().setCredential(PickupCredential.otp('123456'));
      container.read(user.notifier).state = 'op-2';
      await settle();
      expect(read().credential, isNull);
      await settle();
      expect(read().order, isNotNull);
      api.detailError = http(404, 'RESOURCE_NOT_FOUND');
      await ctl().reload();
      expect(read().failure?.kind, FailureKind.notFound); // đơn đang hiện được giữ, báo lỗi nhẹ
      expect(read().order, isNotNull);
    });
  });

  group('màn hình', () {
    Future<void> mount(WidgetTester tester,
        {String? scanText, ScanProblem? problem}) async {
      tester.view.physicalSize = const Size(800, 2600);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      final router = GoRouter(initialLocation: '/operator/pickups/$orderId', routes: [
        GoRoute(path: '/operator', builder: (_, __) => const Scaffold(body: Text('OPERATOR_HOME'))),
        GoRoute(
            path: '/operator/pickups/:id',
            builder: (_, s) => PickupConfirmScreen(orderId: s.pathParameters['id']!)),
        GoRoute(path: '/operator/scan', builder: (_, __) => const QrScanScreen()),
      ]);
      await tester.pumpWidget(ProviderScope(
          overrides: [
            ...overrides(),
            qrScannerBuilderProvider.overrideWithValue((context, {required onCode, required errorView}) {
              if (problem != null) return errorView(problem);
              return Center(
                  child: Column(mainAxisSize: MainAxisSize.min, children: [
                TextButton(
                    key: const Key('fake-scan-valid'),
                    onPressed: () => onCode(validQr),
                    child: const Text('quét hợp lệ')),
                TextButton(
                    key: const Key('fake-scan-other'),
                    onPressed: () => onCode('https://example.com/khuyen-mai'),
                    child: const Text('quét mã khác')),
              ]));
            }),
          ],
          child: MaterialApp.router(routerConfig: router)));
      await tester.pumpAndSettle();
    }

    FilledButton confirmButton(WidgetTester tester) =>
        tester.widget<FilledButton>(find.byKey(const Key('confirm-button')));

    Future<void> addPhoto(WidgetTester tester) async {
      await tester.ensureVisible(find.byKey(const Key('photo-camera')));
      await tester.tap(find.byKey(const Key('photo-camera')));
      await tester.pumpAndSettle();
    }

    testWidgets('quét QR + ảnh + xác nhận: chỉ báo đã giao khi server xác nhận', (tester) async {
      await mount(tester);
      expect(confirmButton(tester).onPressed, isNull);
      expect(find.textContaining('Chưa quét mã QR'), findsOneWidget);
      await tester.tap(find.byKey(const Key('scan-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('fake-scan-valid')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('qr-scanned')), findsOneWidget);
      expect(confirmButton(tester).onPressed, isNull); // vẫn thiếu ảnh
      await addPhoto(tester);
      expect(confirmButton(tester).onPressed, isNotNull);

      api.gate = Completer<void>();
      await tester.ensureVisible(find.byKey(const Key('confirm-button')));
      await tester.tap(find.byKey(const Key('confirm-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('confirm-ok')));
      await tester.pump();
      expect(confirmButton(tester).onPressed, isNull); // đang gửi: không bấm đúp được
      expect(find.byKey(const Key('confirm-success')), findsNothing); // chưa có xác nhận của server
      api.gate!.complete();
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('confirm-success')), findsOneWidget);
      expect(api.sent.length, 1);
      expect(api.sent.single.credential.kind, CredentialKind.qr);
      await tester.tap(find.byKey(const Key('confirm-done')));
      await tester.pumpAndSettle();
      expect(find.text('OPERATOR_HOME'), findsOneWidget);
    });

    testWidgets('quét phải mã QR không liên quan: bỏ qua và quét tiếp', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('scan-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('fake-scan-other')));
      await tester.pumpAndSettle();
      expect(find.textContaining('không phải mã nhận hàng'), findsOneWidget);
      expect(find.byKey(const Key('fake-scan-valid')), findsOneWidget); // vẫn ở màn quét
      await tester.tap(find.byKey(const Key('fake-scan-valid')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('qr-scanned')), findsOneWidget);
    });

    testWidgets('màn quét: có nút quay lại và đèn flash, bấm flash đổi trạng thái', (tester) async {
      await mount(tester);
      await tester.tap(find.byKey(const Key('scan-button')));
      await tester.pumpAndSettle();
      expect(find.text('Quét mã nhận hàng'), findsOneWidget);
      // Thanh nút phải ở mép TRÊN màn hình (từng bị đẩy xuống giữa do Stack kéo giãn SafeArea).
      expect(tester.getTopLeft(find.byKey(const Key('scan-close'))).dy, lessThan(120));
      expect(tester.getTopLeft(find.byKey(const Key('scan-torch'))).dy, lessThan(120));
      expect(find.byTooltip('Bật đèn flash'), findsOneWidget);
      final scope = ProviderScope.containerOf(tester.element(find.byType(QrScanScreen)));
      expect(scope.read(scanTorchProvider), isFalse);
      await tester.tap(find.byKey(const Key('scan-torch')));
      await tester.pump();
      expect(scope.read(scanTorchProvider), isTrue);
      expect(find.byTooltip('Tắt đèn flash'), findsOneWidget);
      // Quay lại không trả mã nào về màn xác nhận.
      await tester.tap(find.byKey(const Key('scan-close')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('scan-button')), findsOneWidget);
      expect(find.byKey(const Key('qr-scanned')), findsNothing);
    });

    testWidgets('nút xác nhận nằm trong thanh hành động cố định ở đáy', (tester) async {
      await mount(tester);
      expect(
          find.descendant(
              of: find.byType(BottomActionBar), matching: find.byKey(const Key('confirm-button'))),
          findsOneWidget);
      // Dòng nhắc thiếu gì cũng nằm trong thanh đó.
      expect(
          find.descendant(
              of: find.byType(BottomActionBar), matching: find.byKey(const Key('confirm-missing'))),
          findsOneWidget);
    });

    testWidgets('camera bị từ chối quyền: hướng dẫn và quay lại để nhập OTP', (tester) async {
      await mount(tester, problem: ScanProblem.permissionDenied);
      await tester.tap(find.byKey(const Key('scan-button')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('scan-problem')), findsOneWidget);
      expect(find.textContaining('quyền camera'), findsOneWidget);
      await tester.tap(find.byKey(const Key('scan-back')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('scan-button')), findsOneWidget); // về màn xác nhận
      await tester.tap(find.text('Nhập OTP'));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('otp-field')), findsOneWidget);
    });

    testWidgets('OTP: chỉ nhận chữ số, báo thiếu số, mã sai thì xóa ô và hiện số lần còn lại', (tester) async {
      await mount(tester);
      await addPhoto(tester);
      await tester.tap(find.text('Nhập OTP'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('otp-field')), '12ab');
      await tester.pump();
      expect(tester.widget<TextField>(find.byKey(const Key('otp-field'))).controller!.text, '12'); // chữ bị loại
      expect(find.text('OTP chưa đủ số chữ số.'), findsOneWidget);
      expect(confirmButton(tester).onPressed, isNull);

      await tester.enterText(find.byKey(const Key('otp-field')), '000000');
      await tester.pump();
      expect(confirmButton(tester).onPressed, isNotNull);
      api.confirmError =
          http(422, 'PICKUP_CODE_INVALID', 'Mã nhận hàng không đúng. Còn 4 lần thử');
      await tester.ensureVisible(find.byKey(const Key('confirm-button')));
      await tester.tap(find.byKey(const Key('confirm-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('confirm-ok')));
      await tester.pumpAndSettle();
      expect(find.textContaining('Còn 4 lần thử'), findsOneWidget);
      expect(find.byKey(const Key('confirm-success')), findsNothing);
      expect(tester.widget<TextField>(find.byKey(const Key('otp-field'))).controller!.text, isEmpty);
    });

    testWidgets('mất mạng: "Đơn CHƯA được giao", yêu cầu được lưu và tự gửi khi đồng bộ', (tester) async {
      await mount(tester);
      await tester.tap(find.text('Nhập OTP'));
      await tester.pumpAndSettle();
      await addPhoto(tester);
      await tester.enterText(find.byKey(const Key('otp-field')), '123456');
      await tester.pump();
      api.confirmError = offline();
      await tester.ensureVisible(find.byKey(const Key('confirm-button')));
      await tester.tap(find.byKey(const Key('confirm-button')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('confirm-ok')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('confirm-queued')), findsOneWidget);
      expect(find.textContaining('CHƯA được giao'), findsOneWidget);
      expect(find.byKey(const Key('confirm-success')), findsNothing);
      expect(find.byKey(const Key('confirm-button')), findsNothing); // không xác nhận lần hai
      expect(store.data['op-1']!.single.kind, PendingKind.confirm);
      final scope = ProviderScope.containerOf(tester.element(find.byType(PickupConfirmScreen)));
      await scope.read(operatorSyncProvider.notifier).syncNow();
      expect(api.sent.length, 2);
      expect(api.sent[1].photoFileId, api.sent[0].photoFileId);
    });

    testWidgets('đơn không còn chờ khách nhận: không có form', (tester) async {
      api.status = 'DELIVERED';
      await mount(tester);
      expect(find.byKey(const Key('confirm-not-pending')), findsOneWidget);
      expect(find.byKey(const Key('confirm-button')), findsNothing);
    });
  });
}
