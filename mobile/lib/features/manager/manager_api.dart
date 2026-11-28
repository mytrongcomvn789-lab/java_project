import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../ai/ai_models.dart';
import '../auth/session.dart';
import '../dispatcher/dispatcher_api.dart' show vnDateParam;

/// Số đơn theo nhóm, đúng `orders` của `GET /v1/statistics/orders/summary` và `/daily`.
class OrderCounts {
  const OrderCounts(
      {this.total = 0,
      this.pending = 0,
      this.inProgress = 0,
      this.delivered = 0,
      this.failed = 0,
      this.returning = 0,
      this.returned = 0,
      this.cancelled = 0,
      this.rejected = 0});
  final int total, pending, inProgress, delivered, failed, returning, returned, cancelled, rejected;

  factory OrderCounts.fromJson(Map<String, dynamic> j) {
    int n(String k) => (j[k] as num?)?.toInt() ?? 0;
    return OrderCounts(
        total: n('total'),
        pending: n('pending'),
        inProgress: n('inProgress'),
        delivered: n('delivered'),
        failed: n('failed'),
        returning: n('returning'),
        returned: n('returned'),
        cancelled: n('cancelled'),
        rejected: n('rejected'));
  }
}

/// Tóm tắt thống kê của một kỳ. Các tỷ lệ là null khi mẫu rỗng: giao diện phải hiện "—", không hiện 0%.
class StatsSummary {
  const StatsSummary(
      {required this.from,
      required this.to,
      required this.orders,
      required this.byStatus,
      this.successRate,
      this.returnRate,
      this.cancelRate,
      this.deliverySample = 0,
      this.totalAttempts = 0,
      this.retryAttempts = 0,
      this.avgDeliverySeconds,
      this.deliveryTimeCount = 0});
  final String from, to;
  final OrderCounts orders;
  final Map<String, int> byStatus;
  final double? successRate, returnRate, cancelRate;
  final int deliverySample, totalAttempts, retryAttempts, deliveryTimeCount;
  final double? avgDeliverySeconds;

  factory StatsSummary.fromJson(Map<String, dynamic> j) {
    final rates = (j['rates'] as Map<String, dynamic>?) ?? const {};
    final attempts = (j['attempts'] as Map<String, dynamic>?) ?? const {};
    final time = (j['deliveryTime'] as Map<String, dynamic>?) ?? const {};
    return StatsSummary(
        from: j['from'] as String? ?? '',
        to: j['to'] as String? ?? '',
        orders: OrderCounts.fromJson((j['orders'] as Map<String, dynamic>?) ?? const {}),
        byStatus: {
          for (final e in ((j['byStatus'] as Map<String, dynamic>?) ?? const {}).entries)
            e.key: (e.value as num).toInt()
        },
        successRate: (rates['successRate'] as num?)?.toDouble(),
        returnRate: (rates['returnRate'] as num?)?.toDouble(),
        cancelRate: (rates['cancelRate'] as num?)?.toDouble(),
        deliverySample: (rates['deliverySample'] as num?)?.toInt() ?? 0,
        totalAttempts: (attempts['totalAttempts'] as num?)?.toInt() ?? 0,
        retryAttempts: (attempts['retryAttempts'] as num?)?.toInt() ?? 0,
        avgDeliverySeconds: (time['avgSeconds'] as num?)?.toDouble(),
        deliveryTimeCount: (time['count'] as num?)?.toInt() ?? 0);
  }
}

class DailyPoint {
  const DailyPoint(this.date, this.orders);
  final String date;
  final OrderCounts orders;
}

/// Tải một trạm trong khung giờ hiện tại (`GET /v1/statistics/stations/load`).
class StationLoad {
  const StationLoad(
      {required this.stationId,
      required this.name,
      required this.status,
      required this.capacity,
      required this.booked,
      required this.processing,
      required this.utilization});
  final String stationId, name, status;
  final int capacity, booked, processing;
  final double utilization;

  factory StationLoad.fromJson(Map<String, dynamic> j) => StationLoad(
      stationId: j['stationId'] as String? ?? '',
      name: j['name'] as String? ?? '',
      status: j['status'] as String? ?? '',
      capacity: (j['capacity'] as num?)?.toInt() ?? 0,
      booked: (j['booked'] as num?)?.toInt() ?? 0,
      processing: (j['processing'] as num?)?.toInt() ?? 0,
      utilization: (j['utilization'] as num?)?.toDouble() ?? 0);
}

class AuditEvent {
  const AuditEvent(
      {required this.id,
      required this.action,
      this.actorEmail,
      this.actorRole,
      this.targetType,
      this.detail,
      this.occurredAt});
  final String id, action;
  final String? actorEmail, actorRole, targetType, detail;
  final DateTime? occurredAt;

  factory AuditEvent.fromJson(Map<String, dynamic> j) => AuditEvent(
      id: j['id'] as String,
      action: j['action'] as String? ?? '',
      actorEmail: j['actorEmail'] as String?,
      actorRole: j['actorRole'] as String?,
      targetType: j['targetType'] as String?,
      detail: j['detail'] as String?,
      occurredAt: DateTime.tryParse('${j['occurredAt']}')?.toLocal());
}

class AuditPage {
  const AuditPage(this.items, this.page, this.pageSize, this.total);
  final List<AuditEvent> items;
  final int page, pageSize, total;
  bool get hasMore => page * pageSize < total;

  factory AuditPage.fromJson(Map<String, dynamic> j) => AuditPage(
      [for (final e in (j['items'] as List<dynamic>? ?? const [])) AuditEvent.fromJson(e as Map<String, dynamic>)],
      (j['page'] as num?)?.toInt() ?? 1,
      (j['pageSize'] as num?)?.toInt() ?? 20,
      (j['total'] as num?)?.toInt() ?? 0);
}

/// Kết quả phân tích vận hành (`POST /v1/ai/analytics`, 7.5). [narrativeMode] cho biết văn bản do AI
/// chọn từ các nhận định đã được Java kiểm chứng hay là bản dự phòng theo quy tắc (không dùng AI).
class AnalyticsResult {
  const AnalyticsResult(
      {required this.insights,
      required this.recommendations,
      required this.narrativeMode,
      required this.warnings});
  final List<String> insights, recommendations, warnings;
  final String narrativeMode;

  bool get isFallback => narrativeMode == 'DETERMINISTIC_FALLBACK';

  static List<String> _strings(Object? v) => v is List ? [for (final e in v) '$e'] : const [];

  factory AnalyticsResult.fromJson(Map<String, dynamic> j) {
    final analysis = (j['analysis'] as Map<String, dynamic>?) ?? const {};
    return AnalyticsResult(
        insights: _strings(j['insights']),
        recommendations: _strings(j['recommendations']),
        narrativeMode: j['narrativeMode'] as String? ?? '',
        warnings: _strings(analysis['warnings']));
  }
}

/// REST cho quản lý hậu cần (chỉ đọc). Quyền do backend quyết định (403 nếu sai vai trò). Không có xuất
/// Excel/PDF trên điện thoại.
class ManagerApi {
  ManagerApi(this._dio);
  final Dio _dio;

  Map<String, dynamic> _range(int days, DateTime now) => {
        'from': vnDateParam(now.subtract(Duration(days: days - 1))),
        'to': vnDateParam(now),
      };

  Future<StatsSummary> summary(int days, {DateTime? now}) => guardApi(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/statistics/orders/summary',
            queryParameters: _range(days, now ?? DateTime.now()));
        return StatsSummary.fromJson(res.data!);
      });

  Future<List<DailyPoint>> daily(int days, {DateTime? now}) => guardApi(() async {
        final res = await _dio.get<List<dynamic>>('/v1/statistics/orders/daily',
            queryParameters: _range(days, now ?? DateTime.now()));
        return [
          for (final e in res.data!)
            DailyPoint((e as Map<String, dynamic>)['date'] as String,
                OrderCounts.fromJson((e['orders'] as Map<String, dynamic>?) ?? const {}))
        ];
      });

  Future<List<StationLoad>> stationLoad() => guardApi(() async {
        final res = await _dio.get<List<dynamic>>('/v1/statistics/stations/load');
        return [for (final e in res.data!) StationLoad.fromJson(e as Map<String, dynamic>)];
      });

  Future<AuditPage> audit({int page = 1, int pageSize = 20, String? action}) => guardApi(() async {
        final res = await _dio.get<Map<String, dynamic>>('/v1/audit-events', queryParameters: {
          'page': page,
          'pageSize': pageSize,
          if (action != null) 'action': action,
        });
        return AuditPage.fromJson(res.data!);
      });

  /// Phân tích vận hành cho kỳ [days] ngày. Có thể tốn hạn mức AI: chỉ gọi khi người dùng bấm.
  Future<AiEnvelope<AnalyticsResult>> analyze(int days, {DateTime? now, CancelToken? cancel}) =>
      guardApi(() async {
        final res = await _dio.post<Map<String, dynamic>>('/v1/ai/analytics',
            data: _range(days, now ?? DateTime.now()),
            options: Options(receiveTimeout: const Duration(seconds: 30)),
            cancelToken: cancel);
        return AiEnvelope.fromJson(res.data!, AnalyticsResult.fromJson);
      });
}

final managerApiProvider =
    Provider<ManagerApi>((ref) => ManagerApi(ref.read(sessionProvider).api));
