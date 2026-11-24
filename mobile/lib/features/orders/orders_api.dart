import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../auth/session.dart';
import 'order_models.dart';

/// REST calls used by order tracking and notifications (contract 3.7/4.4/4.9).
class OrdersApi {
  OrdersApi(this._dio);
  final Dio _dio;

  Future<List<OrderListItem>> listOrders() async {
    final res = await _dio.get<Map<String, dynamic>>('/v1/orders',
        queryParameters: {'page': 1, 'pageSize': 50, 'sort': 'createdAt,desc'});
    return [
      for (final e in (res.data!['items'] as List<dynamic>))
        OrderListItem.fromJson(e as Map<String, dynamic>)
    ];
  }

  Future<TrackingSnapshot> tracking(String orderId) async {
    final res =
        await _dio.get<Map<String, dynamic>>('/v1/orders/$orderId/tracking');
    return TrackingSnapshot.fromJson(res.data!);
  }

  Future<List<AppNotification>> notifications() async {
    final res = await _dio.get<Map<String, dynamic>>('/v1/notifications',
        queryParameters: {'page': 1, 'pageSize': 30});
    return [
      for (final e in (res.data!['items'] as List<dynamic>))
        AppNotification.fromJson(e as Map<String, dynamic>)
    ];
  }

  Future<void> markRead(String notificationId) =>
      _dio.post<void>('/v1/notifications/$notificationId/read');
}

final ordersApiProvider =
    Provider<OrdersApi>((ref) => OrdersApi(ref.read(sessionProvider).api));
