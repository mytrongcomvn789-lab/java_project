import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../auth/session.dart';
import '../operator/operator_controllers.dart' show OperatorListState, operatorPageSize;
import '../orders/customer_order_models.dart' show OrderDetail;
import 'dispatcher_api.dart';

/// Ba nhóm đơn điều phối viên xử lý, đúng ba trang của web: chờ duyệt, chờ lên lịch, thất bại.
const dispatcherStatuses = ['PENDING', 'APPROVED', 'FAILED'];

/// Id của điều phối viên đang đăng nhập, hoặc null. Dữ liệu gắn với id này nên đăng xuất hoặc đổi
/// tài khoản thì danh sách của người trước bị bỏ ngay.
final dispatcherUserProvider = Provider<String?>((ref) => ref.watch(sessionProvider
    .select((s) => s.phase == SessionPhase.signedIn && s.user?.role == 'DISPATCHER'
        ? s.user!.id
        : null)));

/// Danh sách đơn theo một trạng thái, phân trang 20 đơn; phản hồi muộn của phiên cũ bị bỏ qua.
class DispatcherListController
    extends AutoDisposeFamilyNotifier<OperatorListState, String> {
  int _generation = 0;

  @override
  OperatorListState build(String arg) {
    ref.watch(dispatcherUserProvider);
    ref.onDispose(() => _generation++);
    Future.microtask(reload);
    return const OperatorListState();
  }

  DispatcherApi get _api => ref.read(dispatcherApiProvider);

  Future<void> reload() async {
    final gen = ++_generation;
    final hadItems = state.items.isNotEmpty;
    state = OperatorListState(
        items: state.items, total: state.total, page: state.page, loading: !hadItems);
    try {
      final page = await _api.list(status: arg, page: 1, pageSize: operatorPageSize);
      if (gen != _generation) return;
      state = OperatorListState(
          items: page.items, total: page.total, page: 1, loading: false);
    } catch (e) {
      if (gen != _generation) return;
      final failure = toApiFailure(e);
      state = OperatorListState(
          items: state.items,
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
    state = OperatorListState(
        items: s.items, total: s.total, page: s.page, loading: false, loadingMore: true);
    try {
      final next = await _api.list(status: arg, page: s.page + 1, pageSize: operatorPageSize);
      if (gen != _generation) return;
      final ids = {for (final o in s.items) o.id};
      state = OperatorListState(
          items: [...s.items, ...next.items.where((o) => !ids.contains(o.id))],
          total: next.total,
          page: s.page + 1,
          loading: false);
    } catch (e) {
      if (gen != _generation) return;
      state = OperatorListState(
          items: s.items,
          total: s.total,
          page: s.page,
          loading: false,
          moreError: toApiFailure(e));
    }
  }
}

final dispatcherListProvider = NotifierProvider.autoDispose
    .family<DispatcherListController, OperatorListState, String>(
        DispatcherListController.new);

class DispatcherDetailState {
  const DispatcherDetailState(
      {this.order,
      this.loading = true,
      this.busy = false,
      this.error,
      this.actionError,
      this.notice});
  final OrderDetail? order;
  final bool loading, busy;

  /// Lỗi tải đơn khi chưa có gì để hiện.
  final ApiFailure? error;

  /// Lỗi của thao tác vừa thực hiện (hiện trên màn, không che dữ liệu).
  final ApiFailure? actionError;

  /// Thông tin thường (đã xong, hoặc đơn vừa đổi nên đã tải lại).
  final String? notice;

  DispatcherDetailState copyWith(
          {OrderDetail? order,
          bool? loading,
          bool? busy,
          ApiFailure? error,
          bool clearError = false,
          ApiFailure? actionError,
          String? notice,
          bool clearMessages = false}) =>
      DispatcherDetailState(
          order: order ?? this.order,
          loading: loading ?? this.loading,
          busy: busy ?? this.busy,
          error: clearError ? null : (error ?? this.error),
          actionError: clearMessages ? null : (actionError ?? this.actionError),
          notice: clearMessages ? null : (notice ?? this.notice));
}

/// Chi tiết một đơn và các thao tác của điều phối viên. Mỗi thao tác gửi `If-Match` theo phiên bản
/// đang xem; bị từ chối vì đơn đã đổi thì tải lại bản mới và nói rõ cho người dùng. Chặn bấm đúp
/// bằng cờ [DispatcherDetailState.busy].
class DispatcherDetailController
    extends AutoDisposeFamilyNotifier<DispatcherDetailState, String> {
  int _generation = 0;

  @override
  DispatcherDetailState build(String arg) {
    ref.watch(dispatcherUserProvider);
    ref.onDispose(() => _generation++);
    Future.microtask(reload);
    return const DispatcherDetailState();
  }

  DispatcherApi get _api => ref.read(dispatcherApiProvider);

  Future<void> reload({bool silent = false}) async {
    final gen = ++_generation;
    if (!silent && state.order == null) {
      state = state.copyWith(loading: true, clearError: true);
    }
    try {
      final order = await _api.get(arg);
      if (gen != _generation) return;
      state = state.copyWith(order: order, loading: false, clearError: true);
    } catch (e) {
      if (gen != _generation) return;
      final failure = toApiFailure(e);
      state = state.order == null
          ? state.copyWith(loading: false, error: failure)
          : state.copyWith(loading: false, actionError: silent ? null : failure);
    }
  }

  void dismiss() => state = state.copyWith(clearMessages: true);

  Future<bool> approve() =>
      _run((api, o) => api.approve(o.id, o.version), 'Đã duyệt đơn.');

  Future<bool> reject(String reason) =>
      _run((api, o) => api.reject(o.id, o.version, reason), 'Đã từ chối đơn.');

  /// Lên lịch đơn APPROVED hoặc giao lại đơn FAILED vào khung giờ [slotStart].
  Future<bool> schedule(DateTime slotStart) => _run(
      (api, o) => api.schedule(o.id, o.version, slotStart),
      state.order?.status == 'FAILED' ? 'Đã đặt lịch giao lại.' : 'Đã lên lịch giao.');

  Future<bool> fail(String reasonCode, {String? note}) => _run(
      (api, o) => api.fail(o.id, o.version, reasonCode, note: note),
      'Đã ghi nhận giao thất bại.');

  Future<bool> startReturn(String reason) => _run(
      (api, o) => api.startReturn(o.id, o.version, reason), 'Đã chuyển sang hoàn hàng.');

  Future<bool> _run(
      Future<OrderDetail> Function(DispatcherApi api, OrderDetail order) call,
      String done) async {
    final order = state.order;
    if (order == null || state.busy) return false;
    state = state.copyWith(busy: true, clearMessages: true);
    try {
      await call(_api, order);
      _generation++; // bỏ phản hồi tải lại đang chờ (nếu có)
      for (final s in dispatcherStatuses) {
        ref.invalidate(dispatcherListProvider(s)); // đơn đổi nhóm
      }
      state = state.copyWith(busy: false, notice: done);
      await reload(silent: true);
      return true;
    } catch (e) {
      final failure = toApiFailure(e);
      if (failure.outcomeUnknown) {
        state = state.copyWith(
            busy: false,
            notice: 'Chưa rõ thao tác đã được thực hiện chưa. Đã tải lại trạng thái đơn.');
        await reload(silent: true);
      } else if (failure.kind == FailureKind.versionConflict ||
          failure.kind == FailureKind.stateConflict) {
        state = state.copyWith(
            busy: false,
            notice: 'Đơn vừa thay đổi nên thao tác CHƯA được thực hiện. Đã tải lại trạng thái mới nhất.');
        await reload(silent: true);
      } else {
        // 400/422 sai dữ liệu, 409 khác (ví dụ khung giờ đã đầy), mất mạng, 403...: giữ nguyên đơn.
        state = state.copyWith(busy: false, actionError: failure);
        if (failure.kind == FailureKind.dataConflict) await reload(silent: true);
      }
      return false;
    }
  }
}

final dispatcherDetailProvider = NotifierProvider.autoDispose
    .family<DispatcherDetailController, DispatcherDetailState, String>(
        DispatcherDetailController.new);

/// Khoá (trạm, ngày) cho bảng khung giờ.
class AvailabilityKey {
  const AvailabilityKey(this.stationId, this.date);
  final String stationId, date;
  @override
  bool operator ==(Object other) =>
      other is AvailabilityKey && other.stationId == stationId && other.date == date;
  @override
  int get hashCode => Object.hash(stationId, date);
}

final availabilityProvider = FutureProvider.autoDispose
    .family<StationAvailability, AvailabilityKey>((ref, key) {
  ref.watch(dispatcherUserProvider);
  return ref.read(dispatcherApiProvider).availability(key.stationId, date: key.date);
});
