import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../auth/session.dart';
import 'customer_order_models.dart';

/// REST cho luồng tạo/xem/hủy đơn của khách (6.2). Mọi lỗi được ném dưới dạng
/// [ApiFailure]. Mobile không tự quyết định hợp lệ/chuyển trạng thái: server là nguồn quyết định.
class CustomerOrdersApi {
  CustomerOrdersApi(this._dio, {Dio? uploadDio}) : _uploadDio = uploadDio ?? Dio();
  final Dio _dio;

  /// Dio riêng, không baseUrl, không Bearer token: URL tải lên là của MinIO.
  final Dio _uploadDio;

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on DioException catch (e) {
      throw toApiFailure(e);
    } on TypeError {
      throw const ApiFailure(
          FailureKind.unknown, 'Dữ liệu máy chủ trả về không đúng định dạng.');
    }
  }

  /// `GET /v1/orders`. [status] null = tất cả. `from`/`to` là ISO-8601 UTC.
  Future<OrderPage> listOrders(
          {int page = 1,
          int pageSize = 20,
          String? status,
          DateTime? from,
          DateTime? to}) =>
      _guard(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/orders',
            queryParameters: {
              'page': page,
              'pageSize': pageSize,
              'sort': 'createdAt,desc',
              if (status != null) 'status': status,
              if (from != null) 'from': from.toUtc().toIso8601String(),
              if (to != null) 'to': to.toUtc().toIso8601String(),
            });
        return OrderPage.fromJson(res.data!);
      });

  /// `GET /v1/orders/{id}`.
  Future<OrderDetail> getOrder(String id) => _guard(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/orders/$id');
        return OrderDetail.fromJson(res.data!);
      });

  /// `POST /v1/orders` với `Idempotency-Key`. Gửi lại cùng key + cùng nội dung
  /// trả đơn cũ (`replayed`), không tạo đơn mới.
  Future<CreatedOrder> createOrder(
          CreateOrderInput input, String idempotencyKey) =>
      _guard(() async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/orders',
            data: input.toJson(),
            options: Options(headers: {'Idempotency-Key': idempotencyKey}));
        return CreatedOrder(OrderDetail.fromJson(res.data!),
            replayed: res.headers.value('idempotent-replayed') == 'true');
      });

  /// `POST /v1/orders/{id}/cancel` với `If-Match` = phiên bản đang xem.
  /// Server trả 412 nếu đơn đã đổi, 409 nếu đơn không còn PENDING.
  Future<OrderDetail> cancelOrder(String id, int version) => _guard(() async {
        final res = await _dio.post<Map<String, dynamic>>(
            '/v1/orders/$id/cancel',
            options: Options(headers: {'If-Match': '"$version"'}));
        return OrderDetail.fromJson(res.data!);
      });

  Future<List<Station>> listStations() => _guard(() async {
        final res = await _dio.get<List<dynamic>>('/v1/stations');
        return [
          for (final e in res.data!) Station.fromJson(e as Map<String, dynamic>)
        ];
      });

  Future<AddressPage> listAddresses({int page = 1, int pageSize = 50}) =>
      _guard(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/addresses',
            queryParameters: {'page': page, 'pageSize': pageSize});
        return AddressPage.fromJson(res.data!);
      });

  Future<Address> createAddress(AddressInput input) => _guard(() async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/addresses',
            data: input.toJson());
        return Address.fromJson(res.data!);
      });

  Future<Address> updateAddress(String id, AddressInput input) =>
      _guard(() async {
        final res = await _dio.put<Map<String, dynamic>>('/v1/addresses/$id',
            data: input.toJson());
        return Address.fromJson(res.data!);
      });

  Future<void> deleteAddress(String id) =>
      _guard(() => _dio.delete<void>('/v1/addresses/$id'));

  /// Tải một ảnh gói hàng lên, trả về `fileId` để đưa vào `photoFileIds`.
  /// 1) xin upload ticket  2) POST multipart tới MinIO (field `file` đặt cuối)
  /// 3) báo hoàn tất để server kiểm tra dung lượng và loại thật.
  Future<String> uploadPackagePhoto(
          {required Uint8List bytes,
          required String contentType,
          required String filename}) =>
      _guard(() async {
        final ticketRes = await _dio.post<Map<String, dynamic>>(
            '/v1/files/upload-tickets',
            data: {
              'purpose': 'PACKAGE_PHOTO',
              'contentType': contentType,
              'sizeBytes': bytes.length,
            });
        final ticket = UploadTicket.fromJson(ticketRes.data!);
        if (ticket.maxSizeBytes > 0 && bytes.length > ticket.maxSizeBytes) {
          throw const ApiFailure(
              FailureKind.fileTooLarge, 'Ảnh vượt quá dung lượng cho phép.');
        }
        final form = FormData();
        for (final e in ticket.fields.entries) {
          form.fields.add(MapEntry(e.key, e.value));
        }
        form.files.add(MapEntry(
            ticket.fileFieldName,
            MultipartFile.fromBytes(bytes,
                filename: filename,
                contentType: DioMediaType.parse(contentType))));
        try {
          await _uploadDio.post<void>(ticket.url, data: form);
        } on DioException catch (e) {
          // Lỗi từ MinIO không theo envelope của backend: báo chung để người dùng thử lại.
          if (e.response != null) {
            throw const ApiFailure(FailureKind.storageUnavailable,
                'Tải ảnh lên không thành công. Vui lòng thử lại.');
          }
          rethrow;
        }
        await _dio.post<void>('/v1/files/${ticket.fileId}/complete');
        return ticket.fileId;
      });
}

final customerOrdersApiProvider = Provider<CustomerOrdersApi>(
    (ref) => CustomerOrdersApi(ref.read(sessionProvider).api));
