import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../../core/realtime/realtime_provider.dart';
import '../notifications/notifications_controller.dart';
import 'customer_order_models.dart';
import 'customer_orders_api.dart';

const orderPageSize = 20;

class OrderListState {
  const OrderListState(
      {this.items = const [],
      this.status,
      this.total = 0,
      this.page = 0,
      this.loading = true,
      this.loadingMore = false,
      this.error,
      this.moreError});

  final List<OrderSummary> items;

  /// null = tất cả trạng thái.
  final String? status;
  final int total, page;

  /// Đang tải trang đầu (lần đầu, đổi bộ lọc hoặc làm mới).
  final bool loading, loadingMore;

  /// Lỗi tải trang đầu (danh sách đang trống) / lỗi tải thêm (giữ nguyên danh sách đã có).
  final ApiFailure? error, moreError;

  bool get hasMore => items.isNotEmpty && items.length < total && page > 0;
  bool get loaded => !loading && error == null;
}

/// Danh sách đơn của khách, lọc theo trạng thái, phân trang từng 20 đơn.
/// Gắn với user đang đăng nhập nên đổi tài khoản không bao giờ thấy danh sách cũ,
/// và tự làm mới khi có thông báo realtime mới.
class OrderListController extends AutoDisposeNotifier<OrderListState> {
  /// Tăng mỗi lần tải lại để bỏ qua phản hồi đến muộn của bộ lọc/phiên cũ.
  int _generation = 0;

  @override
  OrderListState build() {
    ref.watch(realtimeUserProvider);
    ref.listen(notificationsProvider.select((s) => s.incomingSeq),
        (_, __) => reload());
    ref.onDispose(() => _generation++);
    Future.microtask(reload);
    return const OrderListState();
  }

  CustomerOrdersApi get _api => ref.read(customerOrdersApiProvider);

  Future<void> setStatus(String? status) {
    if (status == state.status) return Future.value();
    state = OrderListState(status: status);
    return reload();
  }

  /// Tải lại từ trang 1. Giữ danh sách cũ trên màn hình cho tới khi có dữ liệu mới.
  Future<void> reload() async {
    final gen = ++_generation;
    final hadItems = state.items.isNotEmpty;
    state = OrderListState(
        items: state.items,
        status: state.status,
        total: state.total,
        page: state.page,
        loading: !hadItems);
    try {
      final page = await _api.listOrders(
          page: 1, pageSize: orderPageSize, status: state.status);
      if (gen != _generation) return;
      state = OrderListState(
          items: page.items,
          status: state.status,
          total: page.total,
          page: 1,
          loading: false);
    } catch (e) {
      if (gen != _generation) return;
      final failure = toApiFailure(e);
      // Đã có dữ liệu thì giữ lại và báo lỗi nhẹ; chưa có thì hiện màn lỗi.
      state = OrderListState(
          items: state.items,
          status: state.status,
          total: state.total,
          page: state.page,
          loading: false,
          error: hadItems ? null : failure,
          moreError: hadItems ? failure : null);
    }
  }

  Future<void> loadMore() async {
    final s = state;
    if (s.loading || s.loadingMore || !s.hasMore) return;
    final gen = _generation;
    state = OrderListState(
        items: s.items,
        status: s.status,
        total: s.total,
        page: s.page,
        loading: false,
        loadingMore: true);
    try {
      final next = await _api.listOrders(
          page: s.page + 1, pageSize: orderPageSize, status: s.status);
      if (gen != _generation) return;
      // Đơn mới tạo làm các trang dịch chuyển: loại trùng theo id.
      final ids = {for (final o in s.items) o.id};
      state = OrderListState(
          items: [...s.items, ...next.items.where((o) => !ids.contains(o.id))],
          status: s.status,
          total: next.total,
          page: s.page + 1,
          loading: false);
    } catch (e) {
      if (gen != _generation) return;
      state = OrderListState(
          items: s.items,
          status: s.status,
          total: s.total,
          page: s.page,
          loading: false,
          moreError: toApiFailure(e));
    }
  }
}

final orderListProvider =
    NotifierProvider.autoDispose<OrderListController, OrderListState>(
        OrderListController.new);
