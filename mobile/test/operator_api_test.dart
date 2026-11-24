import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:smartdrone_mobile/core/network/api_failure.dart';
import 'package:smartdrone_mobile/features/operator/operator_api.dart';
import 'package:smartdrone_mobile/features/operator/operator_models.dart';

import 'support/fakes.dart';

const _orderId = '11111111-1111-1111-1111-111111111111';
const _attemptId = '22222222-2222-2222-2222-222222222222';
const _packageId = '33333333-3333-3333-3333-333333333333';
const _photoId = '44444444-4444-4444-4444-444444444444';
final _qr = '55555555-5555-5555-5555-555555555555.${'A' * 43}';

Map<String, dynamic> _orderJson({String status = 'SCHEDULED', int version = 2}) => {
      'id': _orderId,
      'version': version,
      'status': status,
      'statusLabel': 'Đã lên lịch',
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
    };

Map<String, dynamic> _error(String code, String message) =>
    {'status': 0, 'code': code, 'message': message, 'traceId': 't'};

Map<String, dynamic> _confirmationJson() => {
      'id': 'c1',
      'orderId': _orderId,
      'orderVersion': 6,
      'method': 'OTP',
      'confirmedBy': 'u1',
      'stationId': 's2',
      'photoFileId': _photoId,
      'note': 'Khách nhận đủ',
      'confirmedAt': '2026-11-24T15:20:00Z',
    };

({OperatorApi api, FakeAdapter adapter}) _make(
    FutureOr<ResponseBody> Function(RequestOptions) reply,
    {FutureOr<ResponseBody> Function(RequestOptions)? uploadReply}) {
  final adapter = FakeAdapter(reply);
  final dio = Dio(BaseOptions(baseUrl: 'http://api.test'))
    ..httpClientAdapter = adapter;
  final uploadDio = Dio()
    ..httpClientAdapter =
        FakeAdapter(uploadReply ?? (_) => ResponseBody.fromString('', 204));
  return (api: OperatorApi(dio, uploadDio: uploadDio), adapter: adapter);
}

Future<ApiFailure> _failure(Future<Object?> Function() call) async {
  try {
    await call();
  } on ApiFailure catch (e) {
    return e;
  }
  fail('Cần ném ApiFailure');
}

DioException _http(int status, String code, [String message = 'msg']) =>
    DioException(
        requestOptions: RequestOptions(path: '/x'),
        response: Response(
            requestOptions: RequestOptions(path: '/x'),
            statusCode: status,
            data: _error(code, message)));

void main() {
  group('model', () {
    test('AssignedStation: địa chỉ có thể trống', () {
      final s = AssignedStation.fromJson(
          {'id': 's1', 'name': 'Trạm Bắc', 'status': 'ACTIVE', 'address': null});
      expect(s.address, isNull);
      expect(s.isActive, isTrue);
    });

    test('ReceiptContext + ReceiveRequest.fromContext lấy đúng version và id', () {
      final c = ReceiptContext.fromJson({
        'order': _orderJson(),
        'senderName': 'Khách A',
        'packageId': _packageId,
        'attempt': {
          'id': _attemptId,
          'attemptNo': 1,
          'slotStart': '2026-11-24T16:00:00Z',
          'slotEnd': '2026-11-24T16:30:00Z'
        }
      });
      expect(c.attempt.attemptNo, 1);
      final r = ReceiveRequest.fromContext(c, note: '  Nguyên vẹn  ');
      expect(r.version, 2);
      expect(r.ifMatch, '"2"');
      expect(r.toBody(),
          {'attemptId': _attemptId, 'packageId': _packageId, 'note': 'Nguyên vẹn'});
    });

    test('ReceiveRequest bỏ ghi chú trống, không thêm trường lạ', () {
      const r = ReceiveRequest(
          orderId: _orderId,
          version: 0,
          attemptId: _attemptId,
          packageId: _packageId,
          note: '   ');
      expect(r.toBody().keys, {'attemptId', 'packageId'});
    });

    test('ConfirmRequest gửi đúng MỘT trong otp / qrToken', () {
      final otp = ConfirmRequest(
          orderId: _orderId,
          version: 5,
          photoFileId: _photoId,
          credential: PickupCredential.otp(' 123456 '));
      expect(otp.toBody(), {'otp': '123456', 'photoFileId': _photoId});
      final qr = ConfirmRequest(
          orderId: _orderId,
          version: 5,
          photoFileId: _photoId,
          credential: PickupCredential.qr(_qr),
          note: 'ok');
      expect(qr.toBody(),
          {'qrToken': _qr, 'photoFileId': _photoId, 'note': 'ok'});
      expect(qr.toBody().containsKey('otp'), isFalse);
    });

    test('mã nhận hàng không lọt vào toString / log', () {
      final r = ConfirmRequest(
          orderId: _orderId,
          version: 5,
          photoFileId: _photoId,
          credential: PickupCredential.otp('987654'));
      expect('$r', isNot(contains('987654')));
      expect('${PickupCredential.qr(_qr)}', isNot(contains('AAAA')));
    });

    test('kiểm tra nhanh OTP', () {
      expect(PickupCredential.validateOtp('123456'), isNull);
      expect(PickupCredential.validateOtp(' 123456 '), isNull);
      expect(PickupCredential.validateOtp(''), isNotNull);
      expect(PickupCredential.validateOtp('12a456'), isNotNull);
      expect(PickupCredential.validateOtp('123'), isNotNull);
      expect(PickupCredential.validateOtp('1' * 17), isNotNull);
    });

    test('quét QR: nhận đúng hình dạng, bỏ qua mã QR không liên quan', () {
      expect(PickupCredential.fromScan(_qr)?.kind, CredentialKind.qr);
      expect(PickupCredential.fromScan('  $_qr \n')?.value, _qr);
      expect(PickupCredential.fromScan('https://example.com/promo'), isNull);
      expect(PickupCredential.fromScan('hello world'), isNull);
      expect(PickupCredential.fromScan(''), isNull);
      expect(PickupCredential.fromScan(null), isNull);
      expect(PickupCredential.fromScan('${'a' * 36}.${'B' * 300}'), isNull);
    });
  });

  group('api', () {
    test('assignedStations đọc danh sách trạm của operator', () async {
      final t = _make((o) => jsonResponse([
            {'id': 's1', 'name': 'Trạm Bắc', 'status': 'ACTIVE', 'address': '1 A'},
            {'id': 's2', 'name': 'Trạm Nam', 'status': 'MAINTENANCE'}
          ]));
      final list = await t.api.assignedStations();
      expect(t.adapter.requests.single.path, '/v1/stations/monitoring');
      expect(list.map((e) => e.name), ['Trạm Bắc', 'Trạm Nam']);
      expect(list.last.isActive, isFalse);
    });

    test('listReceipts gửi phân trang và dùng đúng endpoint 4.5', () async {
      final t = _make((o) =>
          jsonResponse({'items': [], 'page': 2, 'pageSize': 20, 'total': 25}));
      final page = await t.api.listReceipts(page: 2, stationId: 's1');
      final r = t.adapter.requests.single;
      expect(r.path, '/v1/station-receipts');
      expect(r.queryParameters['page'], 2);
      expect(r.queryParameters['stationId'], 's1');
      expect(r.queryParameters.containsKey('status'), isFalse); // status cố định ở server
      expect(page.hasMore, isFalse);
    });

    test('listAwaitingPickup lọc AWAITING_PICKUP', () async {
      final t = _make((o) =>
          jsonResponse({'items': [], 'page': 1, 'pageSize': 20, 'total': 0}));
      await t.api.listAwaitingPickup();
      final r = t.adapter.requests.single;
      expect(r.path, '/v1/orders');
      expect(r.queryParameters['status'], 'AWAITING_PICKUP');
    });

    test('receive gửi If-Match và body đúng hợp đồng', () async {
      final t = _make((o) => jsonResponse(_orderJson(status: 'PREPARING', version: 3)));
      final res = await t.api.receive(const ReceiveRequest(
          orderId: _orderId,
          version: 2,
          attemptId: _attemptId,
          packageId: _packageId,
          note: 'ok'));
      final r = t.adapter.requests.single;
      expect(r.method, 'POST');
      expect(r.path, '/v1/orders/$_orderId/receipts');
      expect(r.headers['If-Match'], '"2"');
      expect(r.data, {'attemptId': _attemptId, 'packageId': _packageId, 'note': 'ok'});
      expect(res.status, 'PREPARING');
    });

    test('receive: 404 sai trạm, 412 cũ, 409 đã nhận, 428 thiếu phiên bản', () async {
      const req = ReceiveRequest(
          orderId: _orderId, version: 2, attemptId: _attemptId, packageId: _packageId);
      Future<FailureKind> kindOf(int status, String code) async {
        final t = _make((o) => jsonResponse(_error(code, 'm'), status));
        return (await _failure(() => t.api.receive(req))).kind;
      }

      expect(await kindOf(404, 'RESOURCE_NOT_FOUND'), FailureKind.notFound);
      expect(await kindOf(412, 'VERSION_CONFLICT'), FailureKind.versionConflict);
      expect(await kindOf(409, 'INVALID_STATE_TRANSITION'), FailureKind.stateConflict);
      expect(await kindOf(428, 'PRECONDITION_REQUIRED'),
          FailureKind.preconditionRequired);
      expect(await kindOf(403, 'FORBIDDEN'), FailureKind.forbidden);
    });

    test('confirm bằng OTP: header, body và kết quả không replay', () async {
      final t = _make((o) => jsonResponse(_confirmationJson()));
      final res = await t.api.confirm(ConfirmRequest(
          orderId: _orderId,
          version: 5,
          photoFileId: _photoId,
          credential: PickupCredential.otp('123456')));
      final r = t.adapter.requests.single;
      expect(r.path, '/v1/orders/$_orderId/confirmations');
      expect(r.headers['If-Match'], '"5"');
      expect(r.data, {'otp': '123456', 'photoFileId': _photoId});
      expect(res.replayed, isFalse);
      expect(res.confirmation.method, 'OTP');
      expect(res.confirmation.orderVersion, 6);
    });

    test('confirm gửi lại: nhận biết Idempotent-Replayed', () async {
      final t = _make((o) => ResponseBody.fromString(
              jsonEncode(_confirmationJson()), 200, headers: {
            Headers.contentTypeHeader: ['application/json'],
            'idempotent-replayed': ['true']
          }));
      final res = await t.api.confirm(ConfirmRequest(
          orderId: _orderId,
          version: 5,
          photoFileId: _photoId,
          credential: PickupCredential.qr(_qr)));
      expect(res.replayed, isTrue);
      expect(t.adapter.requests.single.data['qrToken'], _qr);
    });

    test('confirm: mã sai, hết hạn, khóa, đã dùng được phân loại riêng', () async {
      final req = ConfirmRequest(
          orderId: _orderId,
          version: 5,
          photoFileId: _photoId,
          credential: PickupCredential.otp('123456'));
      Future<ApiFailure> run(int status, String code, [String m = 'm']) async {
        final t = _make((o) => jsonResponse(_error(code, m), status));
        return _failure(() => t.api.confirm(req));
      }

      final invalid = await run(422, 'PICKUP_CODE_INVALID', 'Còn 4 lần thử');
      expect(invalid.kind, FailureKind.pickupCodeInvalid);
      expect(invalid.message, 'Còn 4 lần thử'); // thông báo của server được giữ
      expect(invalid.isPickupCodeProblem, isTrue);
      expect((await run(410, 'PICKUP_CODE_EXPIRED')).kind, FailureKind.pickupCodeExpired);
      expect((await run(429, 'PICKUP_CODE_LOCKED')).kind, FailureKind.pickupCodeLocked);
      expect((await run(409, 'PICKUP_CODE_USED')).kind, FailureKind.pickupCodeUsed);
      expect((await run(409, 'INVALID_STATE_TRANSITION')).kind, FailureKind.stateConflict);
      expect((await run(404, 'RESOURCE_NOT_FOUND')).kind, FailureKind.notFound);
    });

    test('uploadConfirmationPhoto xin ticket với mục đích CONFIRMATION_PHOTO', () async {
      final calls = <String>[];
      final t = _make((o) {
        calls.add('${o.method} ${o.path}');
        if (o.path == '/v1/files/upload-tickets') {
          expect((o.data as Map)['purpose'], 'CONFIRMATION_PHOTO');
          expect((o.data as Map)['contentType'], 'image/png');
          return jsonResponse({
            'fileId': _photoId,
            'upload': {
              'url': 'http://minio.test/b',
              'method': 'POST',
              'fileFieldName': 'file',
              'fields': {'key': 'k'}
            },
            'maxSizeBytes': 1000
          }, 201);
        }
        return jsonResponse({'id': _photoId});
      }, uploadReply: (o) {
        calls.add('UPLOAD ${o.uri}');
        return ResponseBody.fromString('', 204);
      });
      final id = await t.api.uploadConfirmationPhoto(
          bytes: Uint8List.fromList([1, 2, 3]),
          contentType: 'image/png',
          filename: 'a.png');
      expect(id, _photoId);
      expect(calls, [
        'POST /v1/files/upload-tickets',
        'UPLOAD http://minio.test/b',
        'POST /v1/files/$_photoId/complete'
      ]);
    });

    test('upload dở: MinIO từ chối thì KHÔNG gọi complete và không có fileId', () async {
      final calls = <String>[];
      final t = _make((o) {
        calls.add(o.path);
        return jsonResponse({
          'fileId': _photoId,
          'upload': {'url': 'http://minio.test', 'fields': {}},
          'maxSizeBytes': 100
        }, 201);
      }, uploadReply: (o) => ResponseBody.fromString('denied', 403));
      final f = await _failure(() => t.api.uploadConfirmationPhoto(
          bytes: Uint8List(3), contentType: 'image/png', filename: 'a.png'));
      expect(f.kind, FailureKind.storageUnavailable);
      expect(f.isRetryable, isTrue);
      expect(calls, ['/v1/files/upload-tickets']);
    });

    test('ảnh vượt giới hạn của server bị chặn trước khi tải lên', () async {
      var uploaded = false;
      final t = _make((o) => jsonResponse({
            'fileId': _photoId,
            'upload': {'url': 'http://minio.test', 'fields': {}},
            'maxSizeBytes': 2
          }, 201), uploadReply: (o) {
        uploaded = true;
        return ResponseBody.fromString('', 204);
      });
      final f = await _failure(() => t.api.uploadConfirmationPhoto(
          bytes: Uint8List(3), contentType: 'image/png', filename: 'a.png'));
      expect(f.kind, FailureKind.fileTooLarge);
      expect(f.isRetryable, isFalse);
      expect(uploaded, isFalse);
    });

    test('JSON sai định dạng -> ApiFailure, không crash', () async {
      final t = _make((o) => jsonResponse({'packageId': 1}));
      final f = await _failure(() => t.api.receiptContext(_orderId));
      expect(f.kind, FailureKind.unknown);
    });
  });

  group('ApiFailure.isRetryable (cho hàng đợi 6.5)', () {
    bool retryable(Object e) => toApiFailure(e).isRetryable;

    test('lỗi tạm thời thì thử lại được', () {
      expect(
          retryable(DioException(
              requestOptions: RequestOptions(path: '/x'),
              type: DioExceptionType.connectionError)),
          isTrue);
      expect(
          retryable(DioException(
              requestOptions: RequestOptions(path: '/x'),
              type: DioExceptionType.receiveTimeout)),
          isTrue);
      expect(retryable(_http(500, 'INTERNAL_ERROR')), isTrue);
      expect(retryable(_http(503, 'STORAGE_UNAVAILABLE')), isTrue);
      expect(retryable(_http(429, 'API_KEY_RATE_LIMITED')), isTrue);
    });

    test('lỗi cần người dùng xử lý thì KHÔNG tự thử lại', () {
      expect(retryable(_http(429, 'PICKUP_CODE_LOCKED')), isFalse);
      expect(retryable(_http(422, 'PICKUP_CODE_INVALID')), isFalse);
      expect(retryable(_http(410, 'PICKUP_CODE_EXPIRED')), isFalse);
      expect(retryable(_http(409, 'INVALID_STATE_TRANSITION')), isFalse);
      expect(retryable(_http(412, 'VERSION_CONFLICT')), isFalse);
      expect(retryable(_http(404, 'RESOURCE_NOT_FOUND')), isFalse);
      expect(retryable(_http(400, 'VALIDATION_ERROR')), isFalse);
      expect(retryable(_http(403, 'FORBIDDEN')), isFalse);
    });
  });
}
