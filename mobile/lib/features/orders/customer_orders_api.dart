import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../auth/session.dart';
import '../files/photo_uploader.dart';
import 'customer_order_models.dart';

/// REST cho luồng tạo/xem/hủy đơn của khách (6.2). Mọi lỗi được ném dưới dạng
/// [ApiFailure]. Mobile không tự quyết định hợp lệ/chuyển trạng thái: server là nguồn quyết định.
class CustomerOrdersApi {
  CustomerOrdersApi(Dio dio, {Dio? uploadDio})
      : _dio = dio,
        _uploader = PhotoUploader(dio, uploadDio: uploadDio);
  final Dio _dio;
  final PhotoUploader _uploader;

  /// `GET /v1/orders`. [status] null = tất cả. `from`/`to` là ISO-8601 UTC.
  Future<OrderPage> listOrders(
          {int page = 1,
          int pageSize = 20,
          String? status,
          DateTime? from,
          DateTime? to}) =>
      guardApi(() async {
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
  Future<OrderDetail> getOrder(String id) => guardApi(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/orders/$id');
        return OrderDetail.fromJson(res.data!);
      });

  /// `POST /v1/orders` với `Idempotency-Key`. Gửi lại cùng key + cùng nội dung
  /// trả đơn cũ (`replayed`), không tạo đơn mới.
  Future<CreatedOrder> createOrder(
          CreateOrderInput input, String idempotencyKey) =>
      guardApi(() async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/orders',
            data: input.toJson(),
            options: Options(headers: {'Idempotency-Key': idempotencyKey}));
        return CreatedOrder(OrderDetail.fromJson(res.data!),
            replayed: res.headers.value('idempotent-replayed') == 'true');
      });

  /// `PUT /v1/orders/{id}`: sửa đơn đang chờ duyệt, gửi lại TOÀN BỘ nội dung. `If-Match` = phiên bản đang xem;
  /// server trả 412 nếu đơn đã đổi và 409 nếu đơn không còn chờ duyệt.
  Future<OrderDetail> updateOrder(String id, int version, CreateOrderInput input) =>
      guardApi(() async {
        final res = await _dio.put<Map<String, dynamic>>('/v1/orders/$id',
            data: input.toJson(), options: Options(headers: {'If-Match': '"$version"'}));
        return OrderDetail.fromJson(res.data!);
      });

  /// `POST /v1/orders/{id}/cancel` với `If-Match` = phiên bản đang xem.
  /// Server trả 412 nếu đơn đã đổi, 409 nếu đơn không còn PENDING.
  Future<OrderDetail> cancelOrder(String id, int version) => guardApi(() async {
        final res = await _dio.post<Map<String, dynamic>>(
            '/v1/orders/$id/cancel',
            options: Options(headers: {'If-Match': '"$version"'}));
        return OrderDetail.fromJson(res.data!);
      });

  Future<List<Station>> listStations() => guardApi(() async {
        final res = await _dio.get<List<dynamic>>('/v1/stations');
        return [
          for (final e in res.data!) Station.fromJson(e as Map<String, dynamic>)
        ];
      });

  Future<AddressPage> listAddresses({int page = 1, int pageSize = 50}) =>
      guardApi(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/addresses',
            queryParameters: {'page': page, 'pageSize': pageSize});
        return AddressPage.fromJson(res.data!);
      });

  Future<Address> createAddress(AddressInput input) => guardApi(() async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/addresses',
            data: input.toJson());
        return Address.fromJson(res.data!);
      });

  Future<Address> updateAddress(String id, AddressInput input) =>
      guardApi(() async {
        final res = await _dio.put<Map<String, dynamic>>('/v1/addresses/$id',
            data: input.toJson());
        return Address.fromJson(res.data!);
      });

  Future<void> deleteAddress(String id) =>
      guardApi(() => _dio.delete<void>('/v1/addresses/$id'));

  /// Tải một ảnh gói hàng lên, trả về `fileId` để đưa vào `photoFileIds`
  /// (luồng 3 bước dùng chung ở [PhotoUploader]).
  Future<String> uploadPackagePhoto(
          {required Uint8List bytes,
          required String contentType,
          required String filename}) =>
      _uploader.upload(
          purpose: UploadPurpose.packagePhoto,
          bytes: bytes,
          contentType: contentType,
          filename: filename);
}

final customerOrdersApiProvider = Provider<CustomerOrdersApi>(
    (ref) => CustomerOrdersApi(ref.read(sessionProvider).api));
