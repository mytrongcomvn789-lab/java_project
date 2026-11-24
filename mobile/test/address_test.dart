import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/core/realtime/realtime_provider.dart';
import 'package:smartdrone_mobile/features/addresses/address_controller.dart';
import 'package:smartdrone_mobile/features/addresses/address_screens.dart';
import 'package:smartdrone_mobile/features/orders/customer_order_models.dart';
import 'package:smartdrone_mobile/features/orders/customer_orders_api.dart';

Address addr(int i) => Address(
    id: 'a$i',
    label: 'Nhãn $i',
    recipientName: 'Người $i',
    phone: '090123456$i',
    addressLine: 'Số $i Lê Lợi',
    latitude: 10.0,
    longitude: 106.0);

DioException http(int status, String message) => DioException(
    requestOptions: RequestOptions(path: '/x'),
    response: Response(
        requestOptions: RequestOptions(path: '/x'),
        statusCode: status,
        data: {'code': 'X', 'message': message}));

class FakeAddressApi extends CustomerOrdersApi {
  FakeAddressApi({this.total = 3}) : super(Dio());
  int total;
  final pages = <int>[];
  final created = <AddressInput>[];
  Object? failNext;
  Completer<void>? gate;
  int pageSizeSeen = 0;

  @override
  Future<AddressPage> listAddresses({int page = 1, int pageSize = 50}) async {
    pages.add(page);
    pageSizeSeen = pageSize;
    final f = failNext;
    if (f != null) {
      failNext = null;
      throw f;
    }
    final all = [for (var i = 1; i <= total; i++) addr(i)];
    final from = (page - 1) * pageSize;
    final items = from >= all.length
        ? <Address>[]
        : all.sublist(from, (from + pageSize).clamp(0, all.length));
    return AddressPage(items: items, page: page, pageSize: pageSize, total: total);
  }

  @override
  Future<Address> createAddress(AddressInput input) async {
    created.add(input);
    final g = gate;
    if (g != null) await g.future;
    final f = failNext;
    if (f != null) {
      failNext = null;
      throw f;
    }
    return Address(
        id: 'new',
        label: input.label,
        recipientName: input.recipientName,
        phone: input.phone,
        addressLine: input.addressLine,
        latitude: input.latitude,
        longitude: input.longitude);
  }

  @override
  Future<Address> updateAddress(String id, AddressInput input) async => Address(
      id: id,
      label: input.label,
      recipientName: input.recipientName,
      phone: input.phone,
      addressLine: input.addressLine,
      latitude: input.latitude,
      longitude: input.longitude);

  @override
  Future<void> deleteAddress(String id) async {
    final f = failNext;
    if (f != null) {
      failNext = null;
      throw f;
    }
  }
}

Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 10));

void main() {
  group('AddressValidation', () {
    test('số điện thoại, bắt buộc và tọa độ', () {
      expect(AddressValidation.phone('0901234567'), isNull);
      expect(AddressValidation.phone('+84901234567'), isNull);
      expect(AddressValidation.phone('12ab'), isNotNull);
      expect(AddressValidation.phone('  '), isNotNull);
      expect(AddressValidation.required('  ', name: 'Nhãn'), contains('Nhãn'));
      expect(AddressValidation.required('a' * 501), isNotNull);
      expect(AddressValidation.coordinate('10,5', min: -90, max: 90, name: 'Vĩ độ'), isNull);
      expect(AddressValidation.coordinate('91', min: -90, max: 90, name: 'Vĩ độ'), isNotNull);
      expect(AddressValidation.coordinate('abc', min: -90, max: 90, name: 'Vĩ độ'), isNotNull);
      expect(AddressValidation.coordinate('NaN', min: -90, max: 90, name: 'Vĩ độ'), isNotNull);
      expect(AddressValidation.coordinate('', min: -90, max: 90, name: 'Vĩ độ'), isNotNull);
    });
  });

  group('AddressController', () {
    late FakeAddressApi api;
    late StateProvider<String?> user;
    late ProviderContainer container;

    setUp(() {
      api = FakeAddressApi();
      user = StateProvider<String?>((ref) => 'user-1');
      container = ProviderContainer(overrides: [
        customerOrdersApiProvider.overrideWithValue(api),
        realtimeUserProvider.overrideWith((ref) => ref.watch(user)),
        realtimeClientProvider.overrideWith((ref) => null),
      ]);
      addTearDown(container.dispose);
    });

    AddressListState read() => container.read(addressesProvider);
    AddressController ctl() => container.read(addressesProvider.notifier);
    Future<void> boot() async {
      container.listen(addressesProvider, (_, __) {});
      await settle();
    }

    test('tải đủ mọi trang của sổ địa chỉ', () async {
      api.total = 120;
      await boot();
      expect(read().items.length, 120);
      expect(api.pages, [1, 2, 3]);
    });

    test('thêm, sửa, xóa cập nhật danh sách', () async {
      await boot();
      await ctl().add(const AddressInput(
          label: 'Mới',
          recipientName: 'X',
          phone: '0901234567',
          addressLine: 'Y',
          latitude: 1,
          longitude: 2));
      expect(read().items.first.id, 'new');
      expect(read().items.length, 4);
      await ctl().update(
          'a1',
          const AddressInput(
              label: 'Đã sửa',
              recipientName: 'X',
              phone: '0901234567',
              addressLine: 'Y',
              latitude: 1,
              longitude: 2));
      expect(read().items.firstWhere((a) => a.id == 'a1').label, 'Đã sửa');
      expect(await ctl().remove('a2'), isTrue);
      expect(read().items.any((a) => a.id == 'a2'), isFalse);
    });

    test('xóa lỗi mạng giữ nguyên địa chỉ và báo lỗi; 404 coi như đã xóa', () async {
      await boot();
      api.failNext = DioException.connectionError(
          requestOptions: RequestOptions(), reason: 'offline');
      expect(await ctl().remove('a1'), isFalse);
      expect(read().items.any((a) => a.id == 'a1'), isTrue);
      expect(read().actionError?.kind, FailureKind.offline);
      api.failNext = http(404, 'không có');
      expect(await ctl().remove('a1'), isTrue);
      expect(read().items.any((a) => a.id == 'a1'), isFalse);
    });

    test('lỗi tải lần đầu hiện lỗi; lỗi khi làm mới giữ danh sách cũ', () async {
      api.failNext = http(500, 'boom');
      await boot();
      expect(read().error?.kind, FailureKind.server);
      await ctl().reload();
      expect(read().items.length, 3);
      api.failNext = http(500, 'boom');
      await ctl().reload();
      expect(read().items.length, 3);
      expect(read().error, isNull);
      expect(read().actionError, isNotNull);
    });

    test('đổi tài khoản không còn thấy địa chỉ của user trước', () async {
      await boot();
      expect(read().items.length, 3);
      api.total = 1;
      container.read(user.notifier).state = 'user-2';
      await settle();
      expect(read().items.length, 1);
    });
  });

  group('màn hình', () {
    late FakeAddressApi api;
    Address? picked;

    Widget app() {
      final router = GoRouter(routes: [
        GoRoute(
            path: '/',
            builder: (context, _) => Scaffold(
                body: TextButton(
                    key: const Key('go-pick'),
                    onPressed: () async {
                      picked = await context.push<Address>('/addresses?pick=1');
                    },
                    child: const Text('chọn')))),
        GoRoute(
            path: '/addresses',
            builder: (_, state) =>
                AddressListScreen(pick: state.uri.queryParameters['pick'] == '1')),
      ]);
      return ProviderScope(overrides: [
        customerOrdersApiProvider.overrideWithValue(api),
        realtimeClientProvider.overrideWith((ref) => null),
      ], child: MaterialApp.router(routerConfig: router));
    }

    setUp(() {
      api = FakeAddressApi();
      picked = null;
    });

    Future<void> fillValid(WidgetTester tester) async {
      await tester.enterText(find.byKey(const Key('address-label')), 'Nhà');
      await tester.enterText(find.byKey(const Key('address-name')), 'An');
      await tester.enterText(find.byKey(const Key('address-phone')), '0901234567');
      await tester.enterText(find.byKey(const Key('address-line')), '1 Lê Lợi');
      await tester.enterText(find.byKey(const Key('address-lat')), '10.77');
      await tester.enterText(find.byKey(const Key('address-lng')), '106.7');
    }

    testWidgets('chế độ chọn: chạm một địa chỉ trả nó về màn trước', (tester) async {
      await tester.pumpWidget(app());
      await tester.tap(find.byKey(const Key('go-pick')));
      await tester.pumpAndSettle();
      expect(find.text('Nhãn 2'), findsOneWidget);
      await tester.tap(find.byKey(const Key('address-a2')));
      await tester.pumpAndSettle();
      expect(picked?.id, 'a2');
    });

    testWidgets('form: dữ liệu sai bị chặn, không gọi API', (tester) async {
      await tester.pumpWidget(app());
      await tester.tap(find.byKey(const Key('go-pick')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('add-address')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('save-address')));
      await tester.pumpAndSettle();
      expect(find.text('Số điện thoại không được để trống.'), findsOneWidget);
      expect(api.created, isEmpty);
    });

    testWidgets('form: lỗi server giữ nguyên dữ liệu, sửa rồi gửi lại được', (tester) async {
      await tester.pumpWidget(app());
      await tester.tap(find.byKey(const Key('go-pick')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('add-address')));
      await tester.pumpAndSettle();
      await fillValid(tester);
      api.failNext = http(400, 'Số điện thoại không hợp lệ');
      await tester.tap(find.byKey(const Key('save-address')));
      await tester.pumpAndSettle();
      expect(find.text('Số điện thoại không hợp lệ'), findsOneWidget);
      expect(find.text('0901234567'), findsOneWidget); // vẫn còn dữ liệu đã nhập
      await tester.tap(find.byKey(const Key('save-address')));
      await tester.pumpAndSettle();
      expect(api.created.length, 2);
    });

    testWidgets('form: bấm đúp lưu chỉ gửi một yêu cầu; ở chế độ chọn dùng luôn địa chỉ mới',
        (tester) async {
      await tester.pumpWidget(app());
      await tester.tap(find.byKey(const Key('go-pick')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('add-address')));
      await tester.pumpAndSettle();
      await fillValid(tester);
      api.gate = Completer<void>();
      await tester.tap(find.byKey(const Key('save-address')));
      await tester.pump();
      expect(tester.widget<FilledButton>(find.byKey(const Key('save-address'))).onPressed,
          isNull);
      api.gate!.complete();
      await tester.pumpAndSettle();
      expect(api.created.length, 1);
      expect(api.created.single.latitude, 10.77);
      expect(picked?.id, 'new');
    });

    testWidgets('xóa có xác nhận', (tester) async {
      await tester.pumpWidget(app());
      await tester.tap(find.byKey(const Key('go-pick')));
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('address-menu-a1')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Xóa').last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('delete-dismiss')));
      await tester.pumpAndSettle();
      expect(find.text('Nhãn 1'), findsOneWidget);
      await tester.tap(find.byKey(const Key('address-menu-a1')));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Xóa').last);
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('delete-confirm')));
      await tester.pumpAndSettle();
      expect(find.text('Nhãn 1'), findsNothing);
    });
  });
}
