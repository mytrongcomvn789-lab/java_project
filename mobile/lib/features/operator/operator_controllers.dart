import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../auth/session.dart';
import '../orders/customer_order_models.dart' show OrderDetail, OrderSummary;
import 'operator_api.dart';
import 'operator_models.dart';
import 'operator_sync.dart';

const operatorPageSize = 20;

/// Id của operator đang đăng nhập, hoặc null. Mọi dữ liệu vận hành gắn với id này, nên đăng
/// xuất hoặc đổi tài khoản thì danh sách của người trước bị bỏ ngay, không lọt sang người sau.
final operatorUserProvider = Provider<String?>((ref) => ref.watch(sessionProvider
    .select((s) => s.phase == SessionPhase.signedIn && s.user?.role == 'STATION_OPERATOR'
        ? s.user!.id
        : null)));

/// Các trạm operator được phân công (server đã giới hạn theo người gọi).
final assignedStationsProvider =
    FutureProvider.autoDispose<List<AssignedStation>>((ref) {
  ref.watch(operatorUserProvider);
  return ref.read(operatorApiProvider).assignedStations();
});

class OperatorListState {
  const OperatorListState(
      {this.items = const [],
      this.total = 0,
      this.page = 0,
      this.loading = true,
      this.loadingMore = false,
      this.error,
      this.moreError});
  final List<OrderSummary> items;
  final int total, page;
  final bool loading, loadingMore;

  /// Lỗi tải trang đầu khi chưa có gì để hiện / lỗi tải thêm hoặc làm mới (giữ danh sách cũ).
  final ApiFailure? error, moreError;

  bool get hasMore => items.isNotEmpty && items.length < total && page > 0;
}

/// Đơn chờ nhận kiện: SCHEDULED ở trạm gửi được phân công, do server lọc (4.5). Phân trang
/// từng 20 đơn; phản hồi đến muộn của phiên cũ bị bỏ qua.
class ReceiptListController extends AutoDisposeNotifier<OperatorListState> {
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
        items: state.items,
        total: state.total,
        page: state.page,
        loading: !hadItems);
    try {
      final page = await _api.listReceipts(page: 1, pageSize: operatorPageSize);
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
        items: s.items,
        total: s.total,
        page: s.page,
        loading: false,
        loadingMore: true);
    try {
      final next = await _api.listReceipts(
          page: s.page + 1, pageSize: operatorPageSize);
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

final receiptListProvider =
    NotifierProvider.autoDispose<ReceiptListController, OperatorListState>(
        ReceiptListController.new);

/// Đơn đang chờ khách nhận (AWAITING_PICKUP). Server trả đơn có trạm gửi HOẶC trạm nhận thuộc
/// phạm vi của operator, nhưng chỉ operator của trạm NHẬN mới xác nhận giao được; danh sách
/// này chỉ giữ đơn có trạm nhận nằm trong các trạm được phân công (lọc hiển thị theo danh sách
/// do server cấp, quyền thật vẫn do server kiểm tra). Số đơn mỗi trạm nhỏ nên đọc hết các
/// trang (tối đa [_maxPages] trang) rồi lọc, để không bị trang "thủng" sau khi lọc.
class PickupListController extends AutoDisposeNotifier<OperatorListState> {
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
        final res =
            await _api.listAwaitingPickup(page: page, pageSize: _pageSize);
        if (gen != _generation) return;
        all.addAll(res.items);
        if (!res.hasMore || res.items.isEmpty) break;
      }
      final items = [for (final o in all) if (mine.contains(o.dropoffStationId)) o];
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

final pickupListProvider =
    NotifierProvider.autoDispose<PickupListController, OperatorListState>(
        PickupListController.new);

class ReceiptDetailState {
  const ReceiptDetailState(
      {this.context,
      this.loading = true,
      this.error,
      this.receiving = false,
      this.notice,
      this.actionError,
      this.gone = false,
      this.queued = false});
  final ReceiptContext? context;
  final bool loading, receiving;

  /// Mất mạng nên yêu cầu nhận kiện được lưu và sẽ tự gửi lại: đơn CHƯA được nhận trên server.
  final bool queued;

  /// Lỗi tải chi tiết khi chưa có dữ liệu để hiện.
  final ApiFailure? error;

  /// Thông báo sau khi dữ liệu bị cũ hoặc chưa rõ kết quả và đã tải lại.
  final String? notice;

  /// Lỗi của thao tác nhận (mất mạng, quyền…); đơn vẫn hiện để thử lại.
  final ApiFailure? actionError;

  /// Đơn không còn trong phạm vi (404): không thao tác được nữa.
  final bool gone;

  OrderDetail? get order => context?.order;

  /// Đơn còn chờ nhận kiện (chỉ để quyết định hiện nút; server vẫn kiểm tra).
  bool get canReceive => order?.status == 'SCHEDULED' && !gone && !queued;

  ReceiptDetailState copyWith(
          {ReceiptContext? context,
          bool? loading,
          bool? receiving,
          ApiFailure? error,
          String? notice,
          ApiFailure? actionError,
          bool? gone,
          bool? queued,
          bool clearError = false,
          bool clearNotice = false,
          bool clearActionError = false}) =>
      ReceiptDetailState(
          context: context ?? this.context,
          loading: loading ?? this.loading,
          error: clearError ? null : error ?? this.error,
          receiving: receiving ?? this.receiving,
          notice: clearNotice ? null : notice ?? this.notice,
          actionError: clearActionError ? null : actionError ?? this.actionError,
          gone: gone ?? this.gone,
          queued: queued ?? this.queued);
}

/// Chi tiết một đơn chờ nhận kiện và thao tác nhận. Server quyết định mọi điều kiện (đúng trạm,
/// đúng trạng thái, kiện và lần giao khớp); mobile chỉ gửi đúng `version` đang xem làm `If-Match`
/// và tải lại khi dữ liệu đã cũ hoặc chưa rõ kết quả.
class ReceiptDetailController
    extends AutoDisposeFamilyNotifier<ReceiptDetailState, String> {
  int _generation = 0;

  @override
  ReceiptDetailState build(String orderId) {
    ref.watch(operatorUserProvider);
    ref.onDispose(() => _generation++);
    Future.microtask(reload);
    return const ReceiptDetailState();
  }

  OperatorApi get _api => ref.read(operatorApiProvider);

  Future<void> reload({bool silent = false}) async {
    final gen = ++_generation;
    if (!silent && state.context == null) {
      state = state.copyWith(loading: true, clearError: true);
    }
    try {
      final ctx = await _api.receiptContext(arg);
      if (gen != _generation) return;
      state = state.copyWith(context: ctx, loading: false, clearError: true);
    } catch (e) {
      if (gen != _generation) return;
      final failure = toApiFailure(e);
      if (failure.kind == FailureKind.notFound) {
        state = state.copyWith(loading: false, gone: true, error: failure);
      } else if (failure.kind == FailureKind.stateConflict ||
          failure.kind == FailureKind.dataConflict) {
        // Đơn đã rời trạng thái chờ nhận (không còn lần giao hợp lệ để dựng context).
        state = state.copyWith(
            loading: false,
            error: state.context == null ? failure : null,
            actionError: state.context == null ? null : failure);
      } else {
        state = state.context == null
            ? state.copyWith(loading: false, error: failure)
            : state.copyWith(loading: false, actionError: silent ? null : failure);
      }
    }
  }

  /// Nhận kiện. Trả true chỉ khi server xác nhận (2xx). Gọi lại khi đang gửi bị bỏ qua.
  Future<bool> receive(String? note) async {
    final ctx = state.context;
    if (ctx == null || state.receiving || !state.canReceive) return false;
    state = state.copyWith(
        receiving: true, clearActionError: true, clearNotice: true);
    final request = ReceiveRequest.fromContext(ctx, note: note);
    try {
      await _api.receive(request);
      _generation++; // bỏ phản hồi tải lại đang chờ (nếu có)
      ref.invalidate(receiptListProvider); // đơn rời danh sách chờ nhận
      state = state.copyWith(receiving: false);
      return true;
    } catch (e) {
      final failure = toApiFailure(e);
      if (failure.kind == FailureKind.offline || failure.outcomeUnknown) {
        // Mất mạng: lưu vào hàng đợi (6.5) để tự gửi lại. Khi gửi lại, hàng đợi tải đơn trước và
        // chỉ gửi nếu đơn vẫn chờ nhận ở đúng phiên bản này (nhận kiện không replay được).
        final user = ref.read(operatorUserProvider);
        final saved = user != null &&
            await ref
                .read(operatorSyncProvider.notifier)
                .enqueue(pendingReceive(user, request));
        state = saved
            ? state.copyWith(receiving: false, queued: true)
            : state.copyWith(receiving: false, actionError: failure);
      } else if (failure.kind == FailureKind.notFound) {
        ref.invalidate(receiptListProvider);
        state = state.copyWith(
            receiving: false,
            gone: true,
            actionError: const ApiFailure(FailureKind.notFound,
                'Đơn không còn trong phạm vi của bạn hoặc không tồn tại.'));
      } else if (failure.isStale || failure.outcomeUnknown) {
        // Phiên bản cũ / trạng thái đã đổi (có thể đã được nhận), hoặc chưa rõ kết quả:
        // tải lại bản thật để người dùng thấy trạng thái chính xác rồi quyết định.
        state = state.copyWith(
            receiving: false,
            notice: failure.outcomeUnknown
                ? 'Chưa rõ yêu cầu nhận kiện đã được thực hiện chưa. Đã tải lại trạng thái đơn.'
                : 'Đơn vừa thay đổi (có thể đã được nhận). Đã tải lại trạng thái mới nhất.');
        await reload(silent: true);
      } else {
        state = state.copyWith(receiving: false, actionError: failure);
      }
      return false;
    }
  }

  void dismissNotice() =>
      state = state.copyWith(clearNotice: true, clearActionError: true);
}

final receiptDetailProvider = NotifierProvider.autoDispose
    .family<ReceiptDetailController, ReceiptDetailState, String>(
        ReceiptDetailController.new);
