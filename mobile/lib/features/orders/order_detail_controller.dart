import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../../core/realtime/realtime_provider.dart';
import '../notifications/notifications_controller.dart';
import 'customer_order_models.dart';
import 'customer_orders_api.dart';
import 'order_list_controller.dart';

/// Tên trạm theo id, để hiển thị thay cho UUID. Tải lại khi đổi tài khoản.
final stationsProvider = FutureProvider.autoDispose<List<Station>>((ref) {
  ref.watch(realtimeUserProvider);
  return ref.read(customerOrdersApiProvider).listStations();
});

class OrderDetailState {
  const OrderDetailState(
      {this.order,
      this.loading = true,
      this.error,
      this.cancelling = false,
      this.notice,
      this.actionError});

  final OrderDetail? order;
  final bool loading, cancelling;

  /// Lỗi tải chi tiết khi chưa có dữ liệu để hiện.
  final ApiFailure? error;

  /// Thông báo sau khi dữ liệu bị cũ và đã tải lại (ví dụ đơn vừa được duyệt).
  final String? notice;

  /// Lỗi của thao tác hủy (mạng, quyền…); đơn vẫn hiện bình thường.
  final ApiFailure? actionError;

  OrderDetailState copyWith(
          {OrderDetail? order,
          bool? loading,
          bool? cancelling,
          ApiFailure? error,
          String? notice,
          ApiFailure? actionError,
          bool clearError = false,
          bool clearNotice = false,
          bool clearActionError = false}) =>
      OrderDetailState(
          order: order ?? this.order,
          loading: loading ?? this.loading,
          cancelling: cancelling ?? this.cancelling,
          error: clearError ? null : error ?? this.error,
          notice: clearNotice ? null : notice ?? this.notice,
          actionError: clearActionError ? null : actionError ?? this.actionError);
}

/// Chi tiết một đơn + hủy đơn. Server quyết định đơn có hủy được không; mobile
/// chỉ dùng `version` đang xem làm `If-Match` và tải lại khi dữ liệu đã cũ.
class OrderDetailController
    extends AutoDisposeFamilyNotifier<OrderDetailState, String> {
  int _generation = 0;

  @override
  OrderDetailState build(String orderId) {
    ref.watch(realtimeUserProvider);
    // Có thông báo realtime (đơn đổi trạng thái) thì tải lại để không hiện dữ liệu cũ.
    ref.listen(notificationsProvider.select((s) => s.incomingSeq),
        (_, __) => reload(silent: true));
    ref.onDispose(() => _generation++);
    Future.microtask(reload);
    return const OrderDetailState();
  }

  CustomerOrdersApi get _api => ref.read(customerOrdersApiProvider);

  /// [silent] = không đổi sang màn loading và không xóa lỗi hiện có.
  Future<void> reload({bool silent = false}) async {
    final gen = ++_generation;
    if (!silent && state.order == null) {
      state = state.copyWith(loading: true, clearError: true);
    }
    try {
      final order = await _api.getOrder(arg);
      if (gen != _generation) return;
      state = state.copyWith(order: order, loading: false, clearError: true);
    } catch (e) {
      if (gen != _generation) return;
      final failure = toApiFailure(e);
      // Đã có dữ liệu thì giữ lại; chỉ báo lỗi khi chưa có gì để hiện.
      state = state.order == null
          ? state.copyWith(loading: false, error: failure)
          : state.copyWith(loading: false, actionError: silent ? null : failure);
    }
  }

  /// Hủy đơn đang xem. Trả true nếu server xác nhận đã hủy.
  Future<bool> cancel() async {
    final order = state.order;
    if (order == null || state.cancelling || !order.canCancel) return false;
    state = state.copyWith(
        cancelling: true, clearActionError: true, clearNotice: true);
    try {
      final updated = await _api.cancelOrder(order.id, order.version);
      ref.invalidate(orderListProvider); // danh sách phải thấy trạng thái mới
      _generation++;
      state = state.copyWith(
          order: order.withView(updated), cancelling: false);
      await reload(silent: true); // lấy lại lịch sử có bản ghi hủy
      return true;
    } catch (e) {
      final failure = toApiFailure(e);
      if (failure.isStale || failure.outcomeUnknown) {
        // Đơn đã đổi (412/409) hoặc chưa rõ kết quả: tải lại bản thật rồi báo.
        state = state.copyWith(
            cancelling: false,
            notice: failure.outcomeUnknown
                ? 'Chưa rõ yêu cầu hủy đã được thực hiện chưa. Đã tải lại trạng thái đơn.'
                : 'Đơn vừa thay đổi nên chưa thể hủy. Đã tải lại trạng thái mới nhất.');
        ref.invalidate(orderListProvider);
        await reload(silent: true);
      } else {
        state = state.copyWith(cancelling: false, actionError: failure);
      }
      return false;
    }
  }

  void dismissNotice() =>
      state = state.copyWith(clearNotice: true, clearActionError: true);
}

final orderDetailProvider = NotifierProvider.autoDispose
    .family<OrderDetailController, OrderDetailState, String>(
        OrderDetailController.new);
