import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_provider.dart';
import 'package:smartdrone_mobile/core/theme/app_theme.dart';
import 'package:smartdrone_mobile/features/orders/customer_order_models.dart';
import 'package:smartdrone_mobile/features/orders/customer_orders_api.dart';
import 'package:smartdrone_mobile/features/orders/order_detail_controller.dart';
import 'package:smartdrone_mobile/features/orders/order_form_controller.dart';
import 'package:smartdrone_mobile/features/orders/order_form_screen.dart';
import 'package:smartdrone_mobile/features/orders/photo_picker.dart';

const addr = Address(
    id: 'a1',
    label: 'Nhà',
    recipientName: 'An',
    phone: '0901234567',
    addressLine: '1 Lê Lợi',
    latitude: 1,
    longitude: 2);

OrderDetail pending(String id,
        {int version = 4, String status = 'PENDING', List<String> photos = const ['f1', 'f2']}) =>
    OrderDetail.fromJson({
      'id': id,
      'version': version,
      'status': status,
      'statusLabel': 'x',
      'pickupStationId': 's1',
      'dropoffStationId': 's2',
      'recipient': {'name': 'An', 'phone': '0901234567', 'addressLine': '1 Lê Lợi'},
      'package': {
        'description': 'Hộp cũ',
        'weightGrams': 900,
        'lengthCm': 12.5,
        'widthCm': 8,
        'heightCm': 5,
        'photoFileIds': photos
      },
    });

class EditApi extends CustomerOrdersApi {
  EditApi(this.existing) : super(Dio());
  OrderDetail existing;
  Object? loadError;
  final updates = <({String id, int version, CreateOrderInput input})>[];
  final createCalls = <String>[];
  Object? updateError;

  @override
  Future<List<Station>> listStations() async => const [
        Station(id: 's1', name: 'Trạm Bắc', latitude: 0, longitude: 0, status: 'ACTIVE'),
        Station(id: 's2', name: 'Trạm Nam', latitude: 0, longitude: 0, status: 'ACTIVE'),
      ];

  @override
  Future<AddressPage> listAddresses({int page = 1, int pageSize = 50}) async =>
      AddressPage(items: const [addr], page: 1, pageSize: 50, total: 1);

  @override
  Future<OrderDetail> getOrder(String id) async {
    if (loadError != null) throw loadError!;
    return existing;
  }

  @override
  Future<OrderDetail> updateOrder(String id, int version, CreateOrderInput input) async {
    updates.add((id: id, version: version, input: input));
    final e = updateError;
    if (e != null) {
      updateError = null;
      throw e;
    }
    return pending(id, version: version + 1);
  }

  @override
  Future<CreatedOrder> createOrder(CreateOrderInput input, String key) async {
    createCalls.add(key);
    throw StateError('sửa đơn không được gọi tạo đơn');
  }

  @override
  Future<String> uploadPackagePhoto(
          {required Uint8List bytes, required String contentType, required String filename}) async =>
      'new-file';
}

DioException http(int status, String code, String message) => DioException(
    requestOptions: RequestOptions(path: '/x'),
    response: Response(
        requestOptions: RequestOptions(path: '/x'),
        statusCode: status,
        data: {'code': code, 'message': message}));

const values = OrderFormValues(
    description: 'Hộp mới', weightGrams: 1000, lengthCm: 10, widthCm: 10, heightCm: 10);

void main() {
  group('bộ điều khiển', () {
    late EditApi api;
    late ProviderContainer container;

    setUp(() {
      api = EditApi(pending('o1'));
      container = ProviderContainer(overrides: [
        customerOrdersApiProvider.overrideWithValue(api),
        realtimeUserProvider.overrideWith((ref) => 'u1'),
        realtimeClientProvider.overrideWith((ref) => null),
        photoPickerProvider.overrideWithValue((_) async =>
            PickedPhoto(name: 'a.jpg', bytes: Uint8List.fromList([1, 2, 3]), contentType: null)),
      ]);
      addTearDown(container.dispose);
      container.listen(orderFormProvider, (_, __) {});
    });

    OrderFormController ctl() => container.read(orderFormProvider.notifier);
    OrderFormState read() => container.read(orderFormProvider);

    test('startEdit điền sẵn trạm và giữ ảnh hiện có', () {
      ctl().startEdit(api.existing);
      expect(read().pickupId, 's1');
      expect(read().dropoffId, 's2');
      expect(read().keptPhotoIds, ['f1', 'f2']);
      expect(read().editing?.id, 'o1');
    });

    test('địa chỉ phải chọn lại: thiếu thì không gửi', () async {
      ctl().startEdit(api.existing);
      expect(await ctl().submit(values), isFalse);
      expect(read().error?.message, contains('địa chỉ'));
      expect(api.updates, isEmpty);
    });

    test('gửi PUT đúng phiên bản đang xem kèm ảnh giữ lại; không tạo đơn mới', () async {
      ctl().startEdit(api.existing);
      ctl().setAddress(addr);
      expect(await ctl().submit(values), isTrue);
      final u = api.updates.single;
      expect((u.id, u.version), ('o1', 4));
      expect(u.input.photoFileIds, ['f1', 'f2']);
      expect(u.input.recipientAddressId, 'a1');
      expect(u.input.description, 'Hộp mới');
      expect(api.createCalls, isEmpty);
      expect(read().created?.order.id, 'o1');
    });

    test('bỏ một ảnh giữ lại thì không gửi ảnh đó', () async {
      ctl().startEdit(api.existing);
      ctl().setAddress(addr);
      ctl().removeKeptPhoto('f1');
      await ctl().submit(values);
      expect(api.updates.single.input.photoFileIds, ['f2']);
    });

    test('ảnh mới được thêm vào sau ảnh giữ lại', () async {
      ctl().startEdit(pending('o1', photos: const ['f1']));
      ctl().setAddress(addr);
      await ctl().addPhoto(PhotoSource.gallery);
      await Future<void>.delayed(const Duration(milliseconds: 20));
      await ctl().submit(values);
      expect(api.updates.single.input.photoFileIds, ['f1', 'new-file']);
    });

    test('tổng ảnh giữ lại và ảnh mới không vượt quá giới hạn', () async {
      ctl().startEdit(pending('o1', photos: const ['f1', 'f2', 'f3']));
      await ctl().addPhoto(PhotoSource.gallery);
      expect(read().photos, isEmpty);
      expect(read().photoError, contains('Tối đa'));
    });

    test('đơn đã đổi (412): giữ dữ liệu đang nhập và báo lỗi', () async {
      ctl().startEdit(api.existing);
      ctl().setAddress(addr);
      api.updateError = http(412, 'VERSION_CONFLICT', 'Đơn đã thay đổi');
      expect(await ctl().submit(values), isFalse);
      expect(read().error?.kind, FailureKind.versionConflict);
      expect(read().editing, isNotNull);
      expect(read().created, isNull);
    });
  });

  group('màn hình', () {
    late EditApi api;

    Future<void> mount(WidgetTester tester) async {
      tester.view.physicalSize = const Size(1170, 3200);
      tester.view.devicePixelRatio = 3;
      addTearDown(tester.view.reset);
      final router = GoRouter(initialLocation: '/edit', routes: [
        GoRoute(path: '/edit', builder: (_, __) => const OrderFormScreen(orderId: 'o1')),
        GoRoute(
            path: '/customer/orders/:id/detail',
            builder: (_, s) => Scaffold(body: Text('DETAIL ${s.pathParameters['id']}'))),
        GoRoute(
            path: '/customer/addresses',
            builder: (_, __) => const Scaffold(body: Text('ADDRESSES'))),
      ]);
      await tester.pumpWidget(ProviderScope(
          overrides: [
            customerOrdersApiProvider.overrideWithValue(api),
            realtimeUserProvider.overrideWith((ref) => 'u1'),
            realtimeClientProvider.overrideWith((ref) => null),
            stationsProvider.overrideWith((ref) async => await api.listStations()),
          ],
          child: MaterialApp.router(theme: buildAppTheme(), routerConfig: router)));
      await tester.pumpAndSettle();
    }

    setUp(() => api = EditApi(pending('o1')));

    testWidgets('mở màn sửa: điền sẵn dữ liệu, hiện ảnh hiện có, nút "Lưu thay đổi"', (tester) async {
      await mount(tester);
      expect(find.text('Sửa đơn'), findsOneWidget);
      expect(find.text('Hộp cũ'), findsOneWidget);
      expect(find.text('900'), findsOneWidget);
      expect(find.text('12.5'), findsOneWidget);
      expect(find.text('Ảnh hiện có 1'), findsOneWidget);
      expect(find.text('Lưu thay đổi'), findsOneWidget);
    });

    testWidgets('đơn không còn chờ duyệt: báo và không cho sửa', (tester) async {
      api = EditApi(pending('o1', status: 'APPROVED'));
      await mount(tester);
      expect(find.byKey(const Key('edit-load-error')), findsOneWidget);
      expect(find.text('Lưu thay đổi'), findsNothing);
    });

    testWidgets('không tải được đơn: báo lỗi, có nút về chi tiết', (tester) async {
      api.loadError = http(404, 'RESOURCE_NOT_FOUND', 'Không tìm thấy đơn');
      await mount(tester);
      expect(find.byKey(const Key('edit-load-error')), findsOneWidget);
      await tester.tap(find.byKey(const Key('edit-back')));
      await tester.pumpAndSettle();
      expect(find.text('DETAIL o1'), findsOneWidget);
    });

    testWidgets('chưa chọn lại địa chỉ: không gửi; xoá một ảnh hiện có được', (tester) async {
      await mount(tester);
      // Nút xoá của chip nằm ở mép phải của chip.
      final chip = tester.getRect(find.byKey(const Key('kept-photo-f1')));
      await tester.tapAt(Offset(chip.right - 14, chip.center.dy));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('kept-photo-f1')), findsNothing);
      await tester.tap(find.byKey(const Key('submit-order')));
      await tester.pumpAndSettle();
      expect(api.updates, isEmpty);
      expect(find.byKey(const Key('form-error')), findsOneWidget);
    });
  });
}
