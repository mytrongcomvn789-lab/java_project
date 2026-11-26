import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../orders/customer_order_models.dart'
    show OrderDetail, OrderHistoryEntry, OrderSummary;
import 'operator_api.dart';
import 'operator_controllers.dart';
import 'operator_models.dart';
import 'operator_sync.dart';

/// Đơn đang hoàn về (RETURNING). Server trả đơn có trạm gửi HOẶC trạm nhận thuộc phạm vi, nhưng
/// hàng hoàn về nơi GỬI nên chỉ giữ đơn có trạm gửi nằm trong các trạm được phân công (lọc hiển
/// thị theo danh sách do server cấp; quyền thật vẫn do server kiểm tra, trạm khác trả 404).
class ReturnListController extends AutoDisposeNotifier<OperatorListState> {
  static const _pageSize = 50;
  static const _maxPages = 10;
  int _generation = 0;

  @override
  OperatorListState build() {
    ref.watch(operatorUserProvider);
    ref.onDispose(() => _generation++);
    Future.microtask(reload);
    return const OperatorListState();
  }

  OperatorApi get _api => ref.read(operatorApiProvider);

  Future<void> reload() async {
    final gen = ++_generation;
    final hadItems = state.items.isNotEmpty;
    state = OperatorListState(
        items: state.items, total: state.total, page: 1, loading: !hadItems);
    try {
      final stations = await _api.assignedStations();
      final mine = {for (final s in stations) s.id};
      final all = <OrderSummary>[];
      for (var page = 1; page <= _maxPages; page++) {
        final res = await _api.listReturning(page: page, pageSize: _pageSize);
        if (gen != _generation) return;
        all.addAll(res.items);
        if (!res.hasMore || res.items.isEmpty) break;
      }
      final items = [for (final o in all) if (mine.contains(o.pickupStationId)) o];
      state = OperatorListState(
          items: items, total: items.length, page: 1, loading: false);
    } catch (e) {
      if (gen != _generation) return;
      final failure = toApiFailure(e);
      state = OperatorListState(
          items: state.items,
          total: state.total,
          page: 1,
          loading: false,
          error: hadItems ? null : failure,
          moreError: hadItems ? failure : null);
    }
  }
}

final returnListProvider =
    NotifierProvider.autoDispose<ReturnListController, OperatorListState>(
        ReturnListController.new);

class ReturnDetailState {
  const ReturnDetailState(
      {this.order,
      this.loading = true,
      this.error,
      this.completing = false,
      this.queued = false,
      this.done = false,
      this.notice,
      this.actionError,
      this.gone = false});
  final OrderDetail? order;
  final bool loading, completing;

  /// Mất mạng nên yêu cầu được lưu và sẽ tự gửi lại: đơn CHƯA được xác nhận trên server.
  final bool queued;

  /// Server đã xác nhận (2xx).
  final bool done;
  final ApiFailure? error;
  final String? notice;
  final ApiFailure? actionError;
  final bool gone;

  bool get canComplete =>
      order?.status == 'RETURNING' && !gone && !done && !queued;

  /// Các lần giao thất bại của đơn, mới nhất trước, để operator biết vì sao hàng quay về.
  List<OrderHistoryEntry> get failures => [
        for (final h in (order?.history ?? const <OrderHistoryEntry>[]).reversed)
          if (h.toStatus == 'FAILED' || h.toStatus == 'RETURNING') h
      ];

  ReturnDetailState copyWith(
          {OrderDetail? order,
          bool? loading,
          bool? completing,
          bool? queued,
          bool? done,
          ApiFailure? error,
          String? notice,
          ApiFailure? actionError,
          bool? gone,
          bool clearError = false,
          bool clearNotice = false,
          bool clearActionError = false}) =>
      ReturnDetailState(
          order: order ?? this.order,
          loading: loading ?? this.loading,
          error: clearError ? null : error ?? this.error,
          completing: completing ?? this.completing,
          queued: queued ?? this.queued,
          done: done ?? this.done,
          notice: clearNotice ? null : notice ?? this.notice,
          actionError: clearActionError ? null : actionError ?? this.actionError,
          gone: gone ?? this.gone);
}

/// Xác nhận đã nhận hàng hoàn (4.8). Server kiểm tra trạm gửi, trạng thái và phiên bản; app chỉ
/// gửi đúng `version` đang xem làm `If-Match`. Mất mạng thì lưu vào hàng đợi (6.5) thay vì mất
/// công; khi đó đơn vẫn là RETURNING trên server cho tới khi hàng đợi gửi thành công.
class ReturnDetailController
    extends AutoDisposeFamilyNotifier<ReturnDetailState, String> {
  int _generation = 0;

  @override
  ReturnDetailState build(String orderId) {
    ref.watch(operatorUserProvider);
    ref.onDispose(() => _generation++);
    Future.microtask(reload);
    return const ReturnDetailState();
  }

  OperatorApi get _api => ref.read(operatorApiProvider);

  Future<void> reload({bool silent = false}) async {
    final gen = ++_generation;
    if (!silent && state.order == null) {
      state = state.copyWith(loading: true, clearError: true);
    }
    try {
      final order = await _api.orderDetail(arg);
      if (gen != _generation) return;
      state = state.copyWith(order: order, loading: false, clearError: true);
    } catch (e) {
      if (gen != _generation) return;
      final failure = toApiFailure(e);
      if (failure.kind == FailureKind.notFound) {
        state = state.copyWith(loading: false, gone: true, error: failure);
      } else if (state.order == null) {
        state = state.copyWith(loading: false, error: failure);
      } else {
        state = state.copyWith(
            loading: false, actionError: silent ? null : failure);
      }
    }
  }

  /// Trả true chỉ khi server xác nhận (2xx). Mất mạng hoặc hết thời gian chờ thì lưu vào hàng
  /// đợi và đặt `queued`; gọi lại khi đang gửi bị bỏ qua.
  Future<bool> complete(String? note) async {
    final order = state.order;
    if (order == null || state.completing || !state.canComplete) return false;
    state = state.copyWith(
        completing: true, clearActionError: true, clearNotice: true);
    final request = CompleteReturnRequest(
        orderId: order.id, version: order.version, note: note);
    try {
      await _api.completeReturn(request);
      _generation++;
      ref.invalidate(returnListProvider);
      state = state.copyWith(completing: false, done: true);
      return true;
    } catch (e) {
      final failure = toApiFailure(e);
      if (failure.kind == FailureKind.offline || failure.outcomeUnknown) {
        final user = ref.read(operatorUserProvider);
        final saved = user != null &&
            await ref
                .read(operatorSyncProvider.notifier)
                .enqueue(pendingReturn(user, request));
        state = saved
            ? state.copyWith(completing: false, queued: true)
            : state.copyWith(completing: false, actionError: failure);
      } else if (failure.kind == FailureKind.notFound) {
        ref.invalidate(returnListProvider);
        state = state.copyWith(
            completing: false,
            gone: true,
            actionError: const ApiFailure(FailureKind.notFound,
                'Đơn không còn trong phạm vi của bạn hoặc không tồn tại.'));
      } else if (failure.isStale) {
        state = state.copyWith(
            completing: false,
            notice: 'Đơn vừa thay đổi (có thể đã được xác nhận). Đã tải lại trạng thái mới nhất.');
        await reload(silent: true);
      } else {
        state = state.copyWith(completing: false, actionError: failure);
      }
      return false;
    }
  }

  void dismissNotice() =>
      state = state.copyWith(clearNotice: true, clearActionError: true);
}

final returnDetailProvider = NotifierProvider.autoDispose
    .family<ReturnDetailController, ReturnDetailState, String>(
        ReturnDetailController.new);
