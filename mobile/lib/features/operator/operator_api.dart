import 'dart:typed_data';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../auth/session.dart';
import '../files/photo_uploader.dart';
import '../orders/customer_order_models.dart' show OrderDetail, OrderPage;
import 'operator_models.dart';

/// REST cho vận hành trạm (6.4), vai trò STATION_OPERATOR. Mọi lỗi được ném dưới dạng
/// [ApiFailure]. Mobile không tự quyết định quyền hay chuyển trạng thái: server kiểm tra
/// phạm vi trạm, mã nhận hàng và đổi trạng thái; app không tự đánh dấu DELIVERED.
class OperatorApi {
  OperatorApi(Dio dio, {Dio? uploadDio})
      : _dio = dio,
        _uploader = PhotoUploader(dio, uploadDio: uploadDio);
  final Dio _dio;
  final PhotoUploader _uploader;

  /// Các trạm operator được phân công (server đã giới hạn theo người gọi).
  Future<List<AssignedStation>> assignedStations() => guardApi(() async {
        final res = await _dio.get<List<dynamic>>('/v1/stations/monitoring');
        return [
          for (final e in res.data!)
            AssignedStation.fromJson(e as Map<String, dynamic>)
        ];
      });

  /// Đơn chờ nhận kiện: SCHEDULED ở trạm gửi được phân công (`GET /v1/station-receipts`).
  /// Mục trong trang không có `packageId` và `attemptId`: dùng [receiptContext] khi mở đơn.
  Future<OrderPage> listReceipts(
          {int page = 1, int pageSize = 20, String? stationId}) =>
      guardApi(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/station-receipts',
            queryParameters: {
              'page': page,
              'pageSize': pageSize,
              'sort': 'createdAt,asc',
              if (stationId != null) 'stationId': stationId,
            });
        return OrderPage.fromJson(res.data!);
      });

  /// Đơn đang chờ khách nhận (AWAITING_PICKUP) trong phạm vi trạm của operator. Server trả
  /// đơn có trạm gửi HOẶC trạm nhận thuộc phạm vi; màn hình lọc thêm theo trạm nhận, còn
  /// quyền xác nhận vẫn do server quyết định (trạm khác trả 404).
  Future<OrderPage> listAwaitingPickup(
          {int page = 1, int pageSize = 20, String? stationId}) =>
      guardApi(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/orders',
            queryParameters: {
              'status': 'AWAITING_PICKUP',
              'page': page,
              'pageSize': pageSize,
              'sort': 'createdAt,asc',
              if (stationId != null) 'stationId': stationId,
            });
        return OrderPage.fromJson(res.data!);
      });

  /// Chi tiết đơn (cần `version` làm `If-Match` khi xác nhận giao).
  Future<OrderDetail> orderDetail(String orderId) => guardApi(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/orders/$orderId');
        return OrderDetail.fromJson(res.data!);
      });

  Future<ReceiptContext> receiptContext(String orderId) => guardApi(() async {
        final res = await _dio
            .get<Map<String, dynamic>>('/v1/orders/$orderId/receipt-context');
        return ReceiptContext.fromJson(res.data!);
      });

  /// Nhận kiện: SCHEDULED → PREPARING. Trả đơn đã cập nhật.
  Future<OrderDetail> receive(ReceiveRequest r) => guardApi(() async {
        final res = await _dio.post<Map<String, dynamic>>(
            '/v1/orders/${r.orderId}/receipts',
            data: r.toBody(),
            options: Options(headers: {'If-Match': r.ifMatch}));
        return OrderDetail.fromJson(res.data!);
      });

  /// Tải ảnh xác nhận (mục đích CONFIRMATION_PHOTO) và trả `fileId`. Chỉ dùng `fileId` này
  /// trong [confirm] khi hàm trả về thành công (đã qua bước complete).
  Future<String> uploadConfirmationPhoto(
          {required Uint8List bytes,
          required String contentType,
          required String filename}) =>
      _uploader.upload(
          purpose: UploadPurpose.confirmationPhoto,
          bytes: bytes,
          contentType: contentType,
          filename: filename);

  /// Xác nhận giao hàng cho khách: AWAITING_PICKUP → DELIVERED sau khi server xác minh mã
  /// và quyền ở trạm nhận. `replayed` cho biết server trả lại kết quả của lần gửi trước.
  Future<ConfirmResult> confirm(ConfirmRequest r) => guardApi(() async {
        final res = await _dio.post<Map<String, dynamic>>(
            '/v1/orders/${r.orderId}/confirmations',
            data: r.toBody(),
            options: Options(headers: {'If-Match': r.ifMatch}));
        return ConfirmResult(DeliveryConfirmation.fromJson(res.data!),
            replayed: res.headers.value('idempotent-replayed') == 'true');
      });

  /// Đơn đang hoàn về (RETURNING). Server trả đơn có trạm gửi HOẶC trạm nhận thuộc phạm vi;
  /// màn hình lọc thêm theo trạm GỬI vì hàng hoàn về nơi gửi (4.8).
  Future<OrderPage> listReturning(
          {int page = 1, int pageSize = 20, String? stationId}) =>
      guardApi(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/orders',
            queryParameters: {
              'status': 'RETURNING',
              'page': page,
              'pageSize': pageSize,
              'sort': 'createdAt,asc',
              if (stationId != null) 'stationId': stationId,
            });
        return OrderPage.fromJson(res.data!);
      });

  /// Xác nhận đã nhận hàng hoàn: RETURNING → RETURNED. Trả đơn đã cập nhật.
  Future<OrderDetail> completeReturn(CompleteReturnRequest r) =>
      guardApi(() async {
        final res = await _dio.post<Map<String, dynamic>>(
            '/v1/orders/${r.orderId}/complete-return',
            data: r.toBody(),
            options: Options(headers: {'If-Match': r.ifMatch}));
        return OrderDetail.fromJson(res.data!);
      });

  /// Bằng chứng giao hàng của đơn đã giao (`GET /v1/orders/{id}/confirmation`).
  Future<DeliveryConfirmation> confirmation(String orderId) =>
      guardApi(() async {
        final res = await _dio
            .get<Map<String, dynamic>>('/v1/orders/$orderId/confirmation');
        return DeliveryConfirmation.fromJson(res.data!);
      });
}

final operatorApiProvider =
    Provider<OperatorApi>((ref) => OperatorApi(ref.read(sessionProvider).api));
