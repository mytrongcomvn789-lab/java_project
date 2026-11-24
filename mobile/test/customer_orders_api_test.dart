import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/features/orders/customer_order_models.dart';
import 'package:smartdrone_mobile/features/orders/customer_orders_api.dart';

import 'support/fakes.dart';

const _orderId = '11111111-1111-1111-1111-111111111111';

Map<String, dynamic> _orderJson({String status = 'PENDING', int version = 0}) => {
      'id': _orderId,
      'version': version,
      'status': status,
      'statusLabel': 'Chờ duyệt',
      'pickupStationId': 's1',
      'dropoffStationId': 's2',
      'recipient': {
        'name': 'An',
        'phone': '0901234567',
        'addressLine': '1 Lê Lợi',
        'latitude': 10.7,
        'longitude': 106.6
      },
      'package': {
        'description': 'Hộp quà',
        'weightGrams': 1200,
        'lengthCm': 30,
        'widthCm': 20.5,
        'heightCm': 10,
        'photoFileIds': ['f1']
      },
      'history': [
        {
          'eventId': 'e1',
          'orderVersion': 0,
          'fromStatus': null,
          'toStatus': 'PENDING',
          'occurredAt': '2026-11-20T01:00:00Z'
        }
      ],
      'createdAt': '2026-11-20T01:00:00Z',
      'updatedAt': '2026-11-20T01:00:00Z',
    };

Map<String, dynamic> _error(String code, String message) =>
    {'status': 0, 'code': code, 'message': message, 'traceId': 't'};

({CustomerOrdersApi api, FakeAdapter adapter}) _make(
    FutureOr<ResponseBody> Function(RequestOptions) reply,
    {FutureOr<ResponseBody> Function(RequestOptions)? uploadReply}) {
  final adapter = FakeAdapter(reply);
  final dio = Dio(BaseOptions(baseUrl: 'http://api.test'))
    ..httpClientAdapter = adapter;
  final uploadDio = Dio()
    ..httpClientAdapter =
        FakeAdapter(uploadReply ?? (_) => ResponseBody.fromString('', 204));
  return (api: CustomerOrdersApi(dio, uploadDio: uploadDio), adapter: adapter);
}

Future<ApiFailure> _failure(Future<Object?> Function() call) async {
  try {
    await call();
  } on ApiFailure catch (e) {
    return e;
  }
  fail('Cần ném ApiFailure');
}

void main() {
  group('model', () {
    test('OrderDetail parse đủ trường và chỉ PENDING mới hủy được', () {
      final d = OrderDetail.fromJson(_orderJson());
      expect(d.shortCode, '11111111');
      expect(d.recipient.name, 'An');
      expect(d.package.widthCm, 20.5);
      expect(d.package.photoFileIds, ['f1']);
      expect(d.history.single.toLabel, 'Chờ duyệt');
      expect(d.canCancel, isTrue);
      expect(OrderDetail.fromJson(_orderJson(status: 'APPROVED')).canCancel,
          isFalse);
    });

    test('OrderPage.hasMore dựa vào total', () {
      OrderPage page(int p, int total) => OrderPage.fromJson(
          {'items': [], 'page': p, 'pageSize': 20, 'total': total});
      expect(page(1, 45).hasMore, isTrue);
      expect(page(3, 45).hasMore, isFalse);
      expect(page(1, 20).hasMore, isFalse);
    });

    test('CreateOrderInput chỉ chứa trường server cho phép', () {
      const input = CreateOrderInput(
          pickupStationId: 'a',
          dropoffStationId: 'b',
          recipientAddressId: 'c',
          description: 'x',
          weightGrams: 10,
          lengthCm: 1,
          widthCm: 2,
          heightCm: 3);
      final json = input.toJson();
      expect(json.keys,
          {'pickupStationId', 'dropoffStationId', 'recipientAddressId', 'package'});
      expect((json['package'] as Map).keys, {
        'description',
        'weightGrams',
        'lengthCm',
        'widthCm',
        'heightCm',
        'photoFileIds'
      });
    });
  });

  group('api', () {
    test('listOrders gửi phân trang, lọc trạng thái và khoảng ngày UTC', () async {
      final t = _make((o) => jsonResponse(
          {'items': [], 'page': 2, 'pageSize': 20, 'total': 21}));
      final page = await t.api.listOrders(
          page: 2, status: 'PENDING', from: DateTime.utc(2026, 11, 1));
      expect(page.hasMore, isFalse);
      final q = t.adapter.requests.single.queryParameters;
      expect(q['page'], 2);
      expect(q['status'], 'PENDING');
      expect(q['from'], '2026-11-01T00:00:00.000Z');
      expect(q.containsKey('to'), isFalse);
    });

    test('createOrder gửi Idempotency-Key và nhận biết đơn được phát lại', () async {
      final t = _make((o) => ResponseBody.fromString(
              jsonEncode(_orderJson()), 200, headers: {
            Headers.contentTypeHeader: ['application/json'],
            'idempotent-replayed': ['true']
          }));
      const input = CreateOrderInput(
          pickupStationId: 'a',
          dropoffStationId: 'b',
          recipientAddressId: 'c',
          description: 'x',
          weightGrams: 10,
          lengthCm: 1,
          widthCm: 2,
          heightCm: 3);
      final created = await t.api.createOrder(input, 'key-1');
      expect(created.replayed, isTrue);
      final req = t.adapter.requests.single;
      expect(req.headers['Idempotency-Key'], 'key-1');
      expect(req.method, 'POST');
    });

    test('cancelOrder gửi If-Match đúng phiên bản', () async {
      final t = _make((o) =>
          jsonResponse(_orderJson(status: 'CANCELLED', version: 4)));
      final res = await t.api.cancelOrder(_orderId, 3);
      expect(t.adapter.requests.single.headers['If-Match'], '"3"');
      expect(res.status, 'CANCELLED');
      expect(res.canCancel, isFalse);
    });

    test('cancel khi đơn đã duyệt -> 409 stateConflict, isStale', () async {
      final t = _make((o) => jsonResponse(
          _error('INVALID_STATE_TRANSITION', 'Không thể hủy'), 409));
      final f = await _failure(() => t.api.cancelOrder(_orderId, 0));
      expect(f.kind, FailureKind.stateConflict);
      expect(f.isStale, isTrue);
    });

    test('cancel với dữ liệu cũ -> 412 versionConflict', () async {
      final t = _make((o) =>
          jsonResponse(_error('VERSION_CONFLICT', 'Đơn vừa đổi'), 412));
      final f = await _failure(() => t.api.cancelOrder(_orderId, 0));
      expect(f.kind, FailureKind.versionConflict);
      expect(f.isStale, isTrue);
    });

    test('listStations và listAddresses parse đúng', () async {
      final t = _make((o) => o.path == '/v1/stations'
          ? jsonResponse([
              {
                'id': 's1',
                'name': 'Trạm 1',
                'latitude': 1.0,
                'longitude': 2.0,
                'capacity': 5,
                'status': 'MAINTENANCE'
              }
            ])
          : jsonResponse({
              'items': [
                {
                  'id': 'a1',
                  'label': 'Nhà',
                  'recipientName': 'An',
                  'phone': '0901234567',
                  'addressLine': '1 Lê Lợi',
                  'latitude': 1,
                  'longitude': 2
                }
              ],
              'page': 1,
              'pageSize': 50,
              'total': 1
            }));
      final stations = await t.api.listStations();
      expect(stations.single.isActive, isFalse);
      final addresses = await t.api.listAddresses();
      expect(addresses.items.single.label, 'Nhà');
    });

    test('uploadPackagePhoto: ticket -> MinIO (file cuối cùng) -> complete', () async {
      final calls = <String>[];
      final t = _make((o) {
        calls.add('${o.method} ${o.path}');
        if (o.path == '/v1/files/upload-tickets') {
          expect((o.data as Map)['purpose'], 'PACKAGE_PHOTO');
          expect((o.data as Map)['sizeBytes'], 3);
          return jsonResponse({
            'fileId': 'file-1',
            'upload': {
              'url': 'http://minio.test/bucket',
              'method': 'POST',
              'fileFieldName': 'file',
              'fields': {'key': 'k', 'policy': 'p'}
            },
            'expiresAt': '2026-11-20T01:00:00Z',
            'maxSizeBytes': 1000
          }, 201);
        }
        return jsonResponse({'id': 'file-1'});
      }, uploadReply: (o) {
        calls.add('UPLOAD ${o.uri}');
        final form = o.data as FormData;
        expect(form.fields.map((e) => e.key), ['key', 'policy']);
        expect(form.files.single.key, 'file');
        expect(o.headers.containsKey('Authorization'), isFalse);
        return ResponseBody.fromString('', 204);
      });
      final id = await t.api.uploadPackagePhoto(
          bytes: Uint8List.fromList([1, 2, 3]),
          contentType: 'image/png',
          filename: 'a.png');
      expect(id, 'file-1');
      expect(calls, [
        'POST /v1/files/upload-tickets',
        'UPLOAD http://minio.test/bucket',
        'POST /v1/files/file-1/complete'
      ]);
    });

    test('ảnh vượt maxSizeBytes bị chặn trước khi tải lên', () async {
      var uploaded = false;
      final t = _make((o) => jsonResponse({
            'fileId': 'f',
            'upload': {'url': 'http://minio.test', 'fields': {}},
            'maxSizeBytes': 2
          }, 201), uploadReply: (o) {
        uploaded = true;
        return ResponseBody.fromString('', 204);
      });
      final f = await _failure(() => t.api.uploadPackagePhoto(
          bytes: Uint8List(3), contentType: 'image/png', filename: 'a.png'));
      expect(f.kind, FailureKind.fileTooLarge);
      expect(uploaded, isFalse);
    });

    test('MinIO từ chối -> lỗi tải ảnh, không gọi complete', () async {
      final calls = <String>[];
      final t = _make((o) {
        calls.add(o.path);
        return jsonResponse({
          'fileId': 'f',
          'upload': {'url': 'http://minio.test', 'fields': {}},
          'maxSizeBytes': 100
        }, 201);
      }, uploadReply: (o) => ResponseBody.fromString('denied', 403));
      final f = await _failure(() => t.api.uploadPackagePhoto(
          bytes: Uint8List(3), contentType: 'image/png', filename: 'a.png'));
      expect(f.kind, FailureKind.storageUnavailable);
      expect(calls, ['/v1/files/upload-tickets']);
    });

    test('JSON sai định dạng -> ApiFailure unknown, không crash', () async {
      final t = _make((o) => jsonResponse({'id': 1}));
      final f = await _failure(() => t.api.getOrder(_orderId));
      expect(f.kind, FailureKind.unknown);
    });
  });

  group('toApiFailure', () {
    DioException http(int status, [Object? body]) => DioException(
        requestOptions: RequestOptions(path: '/x'),
        response: Response(
            requestOptions: RequestOptions(path: '/x'),
            statusCode: status,
            data: body));
    DioException net(DioExceptionType t) =>
        DioException(requestOptions: RequestOptions(path: '/x'), type: t);

    test('ánh xạ mã HTTP và code', () {
      expect(toApiFailure(http(400, _error('VALIDATION_ERROR', 'Sai'))).kind,
          FailureKind.validation);
      expect(toApiFailure(http(400, _error('VALIDATION_ERROR', 'Sai'))).message,
          'Sai');
      expect(toApiFailure(http(404)).kind, FailureKind.notFound);
      expect(toApiFailure(http(409, _error('IDEMPOTENCY_KEY_CONFLICT', 'x'))).kind,
          FailureKind.idempotencyConflict);
      expect(toApiFailure(http(409, _error('DATA_CONFLICT', 'x'))).kind,
          FailureKind.dataConflict);
      expect(toApiFailure(http(428)).kind, FailureKind.preconditionRequired);
      expect(toApiFailure(http(413)).kind, FailureKind.fileTooLarge);
      expect(toApiFailure(http(415)).kind, FailureKind.fileTypeNotAllowed);
      expect(toApiFailure(http(429)).kind, FailureKind.rateLimited);
      expect(toApiFailure(http(503, _error('STORAGE_UNAVAILABLE', 'x'))).kind,
          FailureKind.storageUnavailable);
    });

    test('5xx không lộ message nội bộ', () {
      final f = toApiFailure(http(500, _error('INTERNAL_ERROR', 'NPE at Foo.java:12')));
      expect(f.kind, FailureKind.server);
      expect(f.message, isNot(contains('NPE')));
    });

    test('offline khác timeout: chỉ timeout là chưa rõ kết quả', () {
      final offline = toApiFailure(net(DioExceptionType.connectionError));
      final timeout = toApiFailure(net(DioExceptionType.receiveTimeout));
      expect(offline.kind, FailureKind.offline);
      expect(offline.outcomeUnknown, isFalse);
      expect(timeout.kind, FailureKind.timeout);
      expect(timeout.outcomeUnknown, isTrue);
    });
  });

  test('newIdempotencyKey là UUID v4 và khác nhau mỗi lần', () {
    final re = RegExp(
        r'^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$');
    final a = newIdempotencyKey(), b = newIdempotencyKey();
    expect(a, matches(re));
    expect(a, isNot(b));
  });
}
