import 'dart:async';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_provider.dart';
import 'package:smartdrone_mobile/features/addresses/address_screens.dart';
import 'package:smartdrone_mobile/features/orders/customer_order_models.dart';
import 'package:smartdrone_mobile/features/orders/customer_orders_api.dart';
import 'package:smartdrone_mobile/features/orders/order_form_controller.dart';
import 'package:smartdrone_mobile/features/orders/order_form_screen.dart';
import 'package:smartdrone_mobile/features/orders/photo_picker.dart';

const addr1 = Address(
    id: 'a1',
    label: 'Nhà',
    recipientName: 'An',
    phone: '0901234567',
    addressLine: '1 Lê Lợi',
    latitude: 1,
    longitude: 2);

OrderDetail created(String id) => OrderDetail.fromJson({
      'id': id,
      'version': 0,
      'status': 'PENDING',
      'statusLabel': 'Chờ duyệt',
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
    });

class FakeFormApi extends CustomerOrdersApi {
  FakeFormApi() : super(Dio());
  final submitted = <({CreateOrderInput input, String key})>[];
  final uploads = <String>[];
  Object? createError;
  Completer<void>? createGate;
  Object? uploadError;
  Completer<void>? uploadGate;
  int orderCounter = 0;
  bool replayed = false;

  @override
  Future<List<Station>> listStations() async => const [
        Station(id: 's1', name: 'Trạm Bắc', latitude: 0, longitude: 0, status: 'ACTIVE'),
        Station(id: 's2', name: 'Trạm Nam', latitude: 0, longitude: 0, status: 'ACTIVE'),
        Station(id: 's3', name: 'Trạm Bảo trì', latitude: 0, longitude: 0, status: 'MAINTENANCE'),
      ];

  @override
  Future<AddressPage> listAddresses({int page = 1, int pageSize = 50}) async =>
      AddressPage(items: const [addr1], page: 1, pageSize: 50, total: 1);

  @override
  Future<String> uploadPackagePhoto(
      {required Uint8List bytes,
      required String contentType,
      required String filename}) async {
    uploads.add(filename);
    final g = uploadGate;
    if (g != null) await g.future;
    final e = uploadError;
    if (e != null) {
      uploadError = null;
      throw e;
    }
    return 'file-${uploads.length}';
  }

  @override
  Future<CreatedOrder> createOrder(CreateOrderInput input, String key) async {
    submitted.add((input: input, key: key));
    final g = createGate;
    if (g != null) await g.future;
    final e = createError;
    if (e != null) {
      createError = null;
      throw e;
    }
    return CreatedOrder(created('order-${++orderCounter}'), replayed: replayed);
  }
}

DioException http(int status, String code, String message) => DioException(
    requestOptions: RequestOptions(path: '/x'),
    response: Response(
        requestOptions: RequestOptions(path: '/x'),
        statusCode: status,
        data: {'code': code, 'message': message}));

Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 10));

const values = OrderFormValues(
    description: 'Hộp quà',
    weightGrams: 1200,
    lengthCm: 30,
    widthCm: 20,
    heightCm: 10);

PickedPhoto photo([String name = 'a.jpg']) =>
    PickedPhoto(name: name, bytes: Uint8List.fromList([1, 2, 3]), contentType: null);

void main() {
  late FakeFormApi api;
  late StateProvider<String?> user;
  late ProviderContainer container;
  PickedPhoto? nextPhoto;

  setUp(() {
    api = FakeFormApi();
    nextPhoto = photo();
    user = StateProvider<String?>((ref) => 'user-1');
    container = ProviderContainer(overrides: [
      customerOrdersApiProvider.overrideWithValue(api),
      photoPickerProvider.overrideWithValue((_) async => nextPhoto),
      realtimeUserProvider.overrideWith((ref) => ref.watch(user)),
      realtimeClientProvider.overrideWith((ref) => null),
    ]);
    addTearDown(container.dispose);
    container.listen(orderFormProvider, (_, __) {});
  });

  OrderFormState read() => container.read(orderFormProvider);
  OrderFormController ctl() => container.read(orderFormProvider.notifier);
  void fillSelections() {
    ctl()
      ..setPickup('s1')
      ..setDropoff('s2')
      ..setAddress(addr1);
  }

  group('controller', () {
    test('thiếu trạm/địa chỉ: báo lỗi cục bộ, không gọi API', () async {
      expect(await ctl().submit(values), isFalse);
      expect(read().error?.message, contains('trạm gửi'));
      ctl().setPickup('s1');
      await ctl().submit(values);
      expect(read().error?.message, contains('trạm nhận'));
      ctl().setDropoff('s2');
      await ctl().submit(values);
      expect(read().error?.message, contains('địa chỉ'));
      expect(api.submitted, isEmpty);
    });

    test('gửi thành công: đúng nội dung, có Idempotency-Key, chỉ thành công khi server xác nhận', () async {
      fillSelections();
      expect(read().created, isNull);
      expect(await ctl().submit(values), isTrue);
      final sent = api.submitted.single;
      expect(sent.key, hasLength(36));
      expect(sent.input.recipientAddressId, 'a1');
      expect(sent.input.pickupStationId, 's1');
      expect(sent.input.weightGrams, 1200);
      expect(read().created?.order.id, 'order-1');
      expect(read().submitting, isFalse);
    });

    test('bấm đúp: lần gọi thứ hai bị bỏ qua khi đang gửi', () async {
      fillSelections();
      api.createGate = Completer<void>();
      final a = ctl().submit(values);
      final b = ctl().submit(values);
      expect(await b, isFalse);
      api.createGate!.complete();
      expect(await a, isTrue);
      expect(api.submitted.length, 1);
    });

    test('offline: không có đơn, giữ dữ liệu, gửi lại dùng CÙNG Idempotency-Key', () async {
      fillSelections();
      api.createError = const ApiFailure(FailureKind.offline, 'offline');
      expect(await ctl().submit(values), isFalse);
      expect(read().created, isNull);
      expect(read().error?.kind, FailureKind.offline);
      expect(read().address?.id, 'a1'); // dữ liệu đã chọn còn nguyên
      expect(await ctl().submit(values), isTrue);
      expect(api.submitted.length, 2);
      expect(api.submitted[1].key, api.submitted[0].key);
    });

    test('timeout: chưa rõ kết quả, không báo thành công, gửi lại cùng khóa', () async {
      fillSelections();
      api.createError = const ApiFailure(FailureKind.timeout, 'timeout');
      expect(await ctl().submit(values), isFalse);
      expect(read().error?.outcomeUnknown, isTrue);
      expect(read().created, isNull);
      api.replayed = true; // server đã tạo ở lần đầu, trả lại đơn cũ
      expect(await ctl().submit(values), isTrue);
      expect(read().created?.replayed, isTrue);
      expect(api.submitted[1].key, api.submitted[0].key);
    });

    test('sửa nội dung sau lỗi thì dùng khóa mới (tránh IDEMPOTENCY_KEY_CONFLICT)', () async {
      fillSelections();
      api.createError = http(400, 'VALIDATION_ERROR', 'Trọng lượng tối đa 5000 g');
      expect(await ctl().submit(values), isFalse);
      expect(read().error?.message, 'Trọng lượng tối đa 5000 g');
      await ctl().submit(const OrderFormValues(
          description: 'Hộp quà',
          weightGrams: 900,
          lengthCm: 30,
          widthCm: 20,
          heightCm: 10));
      expect(api.submitted[1].key, isNot(api.submitted[0].key));
    });

    test('địa chỉ không thuộc user: lỗi 400/404 của server được hiển thị', () async {
      fillSelections();
      api.createError = http(404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy địa chỉ');
      expect(await ctl().submit(values), isFalse);
      expect(read().error?.kind, FailureKind.notFound);
      expect(read().created, isNull);
    });

    test('ảnh: tải lên, gắn fileId vào đơn, bỏ qua ảnh đã xóa', () async {
      fillSelections();
      await ctl().addPhoto(PhotoSource.gallery);
      expect(read().photos.single.status, PhotoStatus.done);
      nextPhoto = photo('b.png');
      await ctl().addPhoto(PhotoSource.camera);
      ctl().removePhoto(read().photos.first.id);
      await ctl().submit(values);
      expect(api.submitted.single.input.photoFileIds, ['file-2']);
    });

    test('ảnh lỗi chặn gửi đơn cho tới khi thử lại hoặc xóa', () async {
      fillSelections();
      api.uploadError = http(415, 'FILE_TYPE_NOT_ALLOWED', 'x');
      await ctl().addPhoto(PhotoSource.gallery);
      final p = read().photos.single;
      expect(p.status, PhotoStatus.failed);
      expect(p.failure?.kind, FailureKind.fileTypeNotAllowed);
      expect(await ctl().submit(values), isFalse);
      expect(api.submitted, isEmpty);
      await ctl().retryPhoto(p.id);
      expect(read().photos.single.status, PhotoStatus.done);
      expect(await ctl().submit(values), isTrue);
    });

    test('ảnh đang tải lên chặn gửi đơn', () async {
      fillSelections();
      api.uploadGate = Completer<void>();
      final up = ctl().addPhoto(PhotoSource.gallery);
      await settle();
      expect(read().uploading, isTrue);
      expect(await ctl().submit(values), isFalse);
      expect(api.submitted, isEmpty);
      api.uploadGate!.complete();
      await up;
      expect(await ctl().submit(values), isTrue);
    });

    test('loại ảnh không hỗ trợ bị từ chối, vượt số ảnh bị chặn', () async {
      nextPhoto = photo('a.gif');
      await ctl().addPhoto(PhotoSource.gallery);
      expect(read().photos, isEmpty);
      expect(read().photoError, contains('JPEG'));
      nextPhoto = photo('a.jpg');
      for (var i = 0; i < maxPackagePhotos; i++) {
        await ctl().addPhoto(PhotoSource.gallery);
      }
      await ctl().addPhoto(PhotoSource.gallery);
      expect(read().photos.length, maxPackagePhotos);
      expect(read().photoError, contains('Tối đa'));
    });

    test('người dùng hủy chọn ảnh thì không có gì thay đổi', () async {
      nextPhoto = null;
      await ctl().addPhoto(PhotoSource.gallery);
      expect(read().photos, isEmpty);
      expect(read().photoError, isNull);
    });

    test('đổi tài khoản xóa sạch dữ liệu đang nhập', () async {
      fillSelections();
      container.read(user.notifier).state = 'user-2';
      await settle();
      expect(read().address, isNull);
      expect(read().pickupId, isNull);
    });
  });

  group('màn hình', () {
    Widget app() {
      final router = GoRouter(initialLocation: '/customer/new-order', routes: [
        GoRoute(
            path: '/customer/new-order',
            builder: (_, __) => const OrderFormScreen()),
        GoRoute(
            path: '/customer/addresses',
            builder: (_, state) =>
                AddressListScreen(pick: state.uri.queryParameters['pick'] == '1')),
        GoRoute(
            path: '/customer/orders/:id/detail',
            builder: (_, state) =>
                Scaffold(body: Text('DETAIL ${state.pathParameters['id']}'))),
      ]);
      return ProviderScope(overrides: [
        customerOrdersApiProvider.overrideWithValue(api),
        photoPickerProvider.overrideWithValue((_) async => nextPhoto),
        realtimeClientProvider.overrideWith((ref) => null),
      ], child: MaterialApp.router(routerConfig: router));
    }

    Future<void> pickStation(WidgetTester tester, String key, String name) async {
      await tester.tap(find.byKey(Key(key)));
      await tester.pumpAndSettle();
      await tester.tap(find.text(name).last);
      await tester.pumpAndSettle();
    }

    Future<void> fillAll(WidgetTester tester) async {
      await pickStation(tester, 'pickup-station', 'Trạm Bắc');
      await pickStation(tester, 'dropoff-station', 'Trạm Nam');
      await tester.tap(find.byKey(const Key('pick-address')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('address-a1')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('package-description')), 'Hộp quà');
      await tester.enterText(find.byKey(const Key('package-weight')), '1200');
      await tester.enterText(find.byKey(const Key('package-length')), '30');
      await tester.enterText(find.byKey(const Key('package-width')), '20');
      await tester.enterText(find.byKey(const Key('package-height')), '10');
    }

    testWidgets('chỉ liệt kê trạm đang hoạt động; thiếu dữ liệu thì không gửi', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(app());
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('pickup-station')));
      await tester.pumpAndSettle();
      expect(find.text('Trạm Bắc'), findsWidgets);
      expect(find.text('Trạm Bảo trì'), findsNothing);
      await tester.tap(find.text('Trạm Bắc').last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('submit-order')));
      await tester.pumpAndSettle();
      expect(find.text('Cần nhập.'), findsWidgets);
      expect(api.submitted, isEmpty);
    });

    testWidgets('luồng đầy đủ: nhập, chọn địa chỉ, gửi một lần, chuyển sang chi tiết', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(app());
      await tester.pumpAndSettle();
      await fillAll(tester);
      api.createGate = Completer<void>();
      await tester.tap(find.byKey(const Key('submit-order')));
      await tester.pump();
      // Đang gửi: nút bị vô hiệu hóa nên bấm đúp không gửi thêm.
      expect(tester.widget<FilledButton>(find.byKey(const Key('submit-order'))).onPressed,
          isNull);
      api.createGate!.complete();
      await tester.pumpAndSettle();
      expect(api.submitted.length, 1);
      expect(api.submitted.single.input.description, 'Hộp quà');
      expect(find.text('DETAIL order-1'), findsOneWidget);
    });

    testWidgets('offline: hiện lỗi "chưa được tạo", nút đổi thành Gửi lại, dữ liệu còn nguyên',
        (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(app());
      await tester.pumpAndSettle();
      await fillAll(tester);
      api.createError = const ApiFailure(FailureKind.offline, 'Không kết nối được máy chủ.');
      await tester.tap(find.byKey(const Key('submit-order')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('form-error')), findsOneWidget);
      expect(find.textContaining('CHƯA được tạo'), findsOneWidget);
      expect(find.text('Gửi lại'), findsOneWidget);
      expect(find.text('DETAIL order-1'), findsNothing);
      expect(find.text('Hộp quà'), findsOneWidget);
      await tester.tap(find.byKey(const Key('submit-order')));
      await tester.pumpAndSettle();
      expect(find.text('DETAIL order-1'), findsOneWidget);
      expect(api.submitted[1].key, api.submitted[0].key);
    });

    testWidgets('thêm ảnh hiện ô ảnh, xóa được', (tester) async {
      tester.view.physicalSize = const Size(800, 2400);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      nextPhoto = PickedPhoto(
          name: 'a.png',
          bytes: Uint8List.fromList(_png),
          contentType: 'image/png');
      await tester.pumpWidget(app());
      await tester.pumpAndSettle();
      await tester.ensureVisible(find.byKey(const Key('add-photo')));
      await tester.tap(find.byKey(const Key('add-photo')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('photo-gallery')));
      await tester.pumpAndSettle();
      expect(api.uploads, ['a.png']);
      expect(find.byKey(const Key('remove-photo-0')), findsOneWidget);
      await tester.tap(find.byKey(const Key('remove-photo-0')));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('remove-photo-0')), findsNothing);
    });
  });
}

// PNG 1x1 hợp lệ để Image.memory giải mã được trong test.
const _png = <int>[
  0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
  0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
  0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0xF8, 0xFF, 0xFF, 0x3F,
  0x00, 0x05, 0xFE, 0x02, 0xFE, 0xA7, 0x35, 0x81, 0x84, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
  0x44, 0xAE, 0x42, 0x60, 0x82
];
