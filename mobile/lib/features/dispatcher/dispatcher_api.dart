import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../auth/session.dart';
import '../orders/customer_order_models.dart';
import '../orders/customer_orders_api.dart';

/// Lý do thất bại cố định của backend (4.8). `OTHER` bắt buộc kèm ghi chú.
const failureReasons = <String, String>{
  'RECIPIENT_ABSENT': 'Người nhận không có mặt / không nhận hàng',
  'STATION_UNAVAILABLE': 'Trạm không sẵn sàng',
  'WEATHER': 'Thời tiết xấu',
  'DRONE_FAULT': 'Sự cố drone',
  'OTHER': 'Lý do khác',
};

/// Múi giờ vận hành (backend tính khung giờ theo giờ Việt Nam, UTC+7).
DateTime vnTime(DateTime t) => t.toUtc().add(const Duration(hours: 7));

/// Ngày `yyyy-MM-dd` theo giờ Việt Nam của một thời điểm.
String vnDateParam(DateTime t) {
  final d = vnTime(t);
  String two(int n) => n.toString().padLeft(2, '0');
  return '${d.year}-${two(d.month)}-${two(d.day)}';
}

/// Một khung giờ của trạm (`GET /v1/stations/{id}/availability`).
class SlotInfo {
  const SlotInfo(
      {required this.start,
      required this.end,
      required this.used,
      required this.free,
      required this.bookable});
  final DateTime start, end;
  final int used, free;

  /// Còn chỗ, trạm hoạt động và nằm trong thời gian cho phép đặt. Server vẫn kiểm tra lại khi gửi.
  final bool bookable;

  factory SlotInfo.fromJson(Map<String, dynamic> json) => SlotInfo(
      start: DateTime.parse(json['slotStart'] as String),
      end: DateTime.parse(json['slotEnd'] as String),
      used: (json['used'] as num).toInt(),
      free: (json['free'] as num).toInt(),
      bookable: json['bookable'] == true);
}

class StationAvailability {
  const StationAvailability(
      {required this.stationName,
      required this.capacity,
      required this.slotMinutes,
      required this.slots});
  final String stationName;
  final int capacity, slotMinutes;
  final List<SlotInfo> slots;

  factory StationAvailability.fromJson(Map<String, dynamic> json) => StationAvailability(
      stationName: json['stationName'] as String? ?? '',
      capacity: (json['capacity'] as num?)?.toInt() ?? 0,
      slotMinutes: (json['slotMinutes'] as num?)?.toInt() ?? 30,
      slots: [
        for (final s in (json['slots'] as List<dynamic>? ?? const []))
          SlotInfo.fromJson(s as Map<String, dynamic>)
      ]);
}

/// REST cho điều phối viên. Mọi thay đổi gửi `If-Match` = phiên bản đang xem: server từ chối (412/409)
/// nếu đơn đã đổi, app không tự quyết định hợp lệ hay chuyển trạng thái.
class DispatcherApi {
  DispatcherApi(this._dio) : _orders = CustomerOrdersApi(_dio);
  final Dio _dio;
  final CustomerOrdersApi _orders;

  /// `GET /v1/orders?status=...` (điều phối viên thấy mọi đơn, server phân trang).
  Future<OrderPage> list({required String status, int page = 1, int pageSize = 20}) =>
      _orders.listOrders(page: page, pageSize: pageSize, status: status);

  Future<OrderDetail> get(String id) => _orders.getOrder(id);

  Future<OrderDetail> _post(String path, int version, [Map<String, dynamic>? body]) =>
      guardApi(() async {
        final res = await _dio.post<Map<String, dynamic>>(path,
            data: body, options: Options(headers: {'If-Match': '"$version"'}));
        return OrderDetail.fromJson(res.data!);
      });

  /// PENDING → APPROVED.
  Future<OrderDetail> approve(String id, int version) =>
      _post('/v1/orders/$id/approve', version);

  /// PENDING → REJECTED, bắt buộc lý do.
  Future<OrderDetail> reject(String id, int version, String reason) =>
      _post('/v1/orders/$id/reject', version, {'reason': reason});

  /// APPROVED → SCHEDULED, hoặc giao lại đơn FAILED. [slotStart] là thời điểm UTC.
  Future<OrderDetail> schedule(String id, int version, DateTime slotStart) =>
      _post('/v1/orders/$id/schedules', version,
          {'slotStart': slotStart.toUtc().toIso8601String()});

  /// Đơn đang giao dở → FAILED, bắt buộc `reasonCode` (xem [failureReasons]).
  Future<OrderDetail> fail(String id, int version, String reasonCode, {String? note}) =>
      _post('/v1/orders/$id/fail', version, {
        'reasonCode': reasonCode,
        if (note != null && note.trim().isNotEmpty) 'note': note.trim(),
      });

  /// FAILED → RETURNING, bắt buộc lý do.
  Future<OrderDetail> startReturn(String id, int version, String reason) =>
      _post('/v1/orders/$id/return', version, {'reason': reason});

  /// Chỗ trống từng khung giờ của một trạm trong ngày (`date` dạng yyyy-MM-dd, giờ Việt Nam).
  Future<StationAvailability> availability(String stationId, {String? date}) =>
      guardApi(() async {
        final res = await _dio.get<Map<String, dynamic>>(
            '/v1/stations/$stationId/availability',
            queryParameters: {if (date != null) 'date': date});
        return StationAvailability.fromJson(res.data!);
      });
}

final dispatcherApiProvider =
    Provider<DispatcherApi>((ref) => DispatcherApi(ref.read(sessionProvider).api));
