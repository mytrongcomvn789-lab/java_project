import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../orders/customer_order_models.dart' show OrderSummary;
import 'operator_api.dart';
import 'operator_controllers.dart';

/// Đơn đang trên đường đi mà nhân viên trạm có việc phải làm:
///  * PREPARING ở trạm GỬI của mình: bấm "Xuất phát" (DISPATCH);
///  * IN_TRANSIT tới trạm NHẬN của mình: bấm "Drone đến" (ARRIVE).
/// Server trả đơn có trạm gửi HOẶC trạm nhận thuộc phạm vi nên màn hình lọc thêm đúng trạm; quyền thật
/// vẫn do server kiểm tra (trạm sai trả 404).
class TransitListController extends AutoDisposeNotifier<OperatorListState> {
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

  Future<List<OrderSummary>> _all(String status) async {
    final all = <OrderSummary>[];
    for (var page = 1; page <= _maxPages; page++) {
      final res = await _api.listByStatus(status, page: page, pageSize: _pageSize);
      all.addAll(res.items);
      if (!res.hasMore || res.items.isEmpty) break;
    }
    return all;
  }

  Future<void> reload() async {
    final gen = ++_generation;
    final hadItems = state.items.isNotEmpty;
    state = OperatorListState(
        items: state.items, total: state.total, page: 1, loading: !hadItems);
    try {
      final stations = await _api.assignedStations();
      final mine = {for (final s in stations) s.id};
      final preparing = await _all('PREPARING');
      final inTransit = await _all('IN_TRANSIT');
      if (gen != _generation) return;
      final items = [
        for (final o in inTransit) if (mine.contains(o.dropoffStationId)) o,
        for (final o in preparing) if (mine.contains(o.pickupStationId)) o,
      ];
      state = OperatorListState(items: items, total: items.length, page: 1, loading: false);
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

final transitListProvider =
    NotifierProvider.autoDispose<TransitListController, OperatorListState>(
        TransitListController.new);

/// Nhãn nút theo trạng thái: null nghĩa là đơn không có thao tác ở tab này.
String? transitActionLabel(String status) => switch (status) {
      'PREPARING' => 'Xuất phát',
      'IN_TRANSIT' => 'Drone đến',
      _ => null,
    };

/// Kết quả của một lần bấm Xuất phát / Drone đến.
class TransitResult {
  const TransitResult(this.ok, this.message);
  final bool ok;
  final String message;
}

/// Thực hiện Xuất phát / Drone đến. Luôn đọc lại đơn để lấy phiên bản và trạng thái mới nhất rồi gửi
/// `If-Match`; nếu đơn không còn ở trạng thái mong đợi thì KHÔNG gửi. Không có hàng đợi offline vì quyết
/// định cần dữ liệu mới nhất (xuất phát sai thời điểm là chuyện thật ngoài hiện trường).
class TransitActionController extends AutoDisposeNotifier<Set<String>> {
  @override
  Set<String> build() => const {};

  OperatorApi get _api => ref.read(operatorApiProvider);

  Future<TransitResult> run(String orderId, String expectedStatus) async {
    if (state.contains(orderId)) return const TransitResult(false, '');
    state = {...state, orderId};
    try {
      final order = await _api.orderDetail(orderId);
      if (order.status != expectedStatus) {
        _refresh();
        return TransitResult(false,
            'Đơn đã chuyển sang "${order.statusLabel}" nên thao tác CHƯA được thực hiện. Đã tải lại danh sách.');
      }
      if (expectedStatus == 'PREPARING') {
        await _api.dispatch(orderId, order.version);
      } else {
        await _api.arrive(orderId, order.version);
      }
      _refresh();
      return TransitResult(
          true, expectedStatus == 'PREPARING' ? 'Đã xuất phát, đơn đang giao.' : 'Đã ghi nhận drone đến trạm.');
    } catch (e) {
      final f = toApiFailure(e);
      if (f.isStale) {
        _refresh();
        return const TransitResult(false,
            'Đơn vừa thay đổi nên thao tác CHƯA được thực hiện. Đã tải lại danh sách.');
      }
      if (f.outcomeUnknown) {
        _refresh();
        return const TransitResult(
            false, 'Chưa rõ thao tác đã được thực hiện chưa. Đã tải lại danh sách để kiểm tra.');
      }
      return TransitResult(false, f.message);
    } finally {
      state = {...state}..remove(orderId);
    }
  }

  void _refresh() {
    ref.invalidate(transitListProvider);
    ref.invalidate(pickupListProvider);
    ref.invalidate(receiptListProvider);
  }
}

final transitActionProvider =
    NotifierProvider.autoDispose<TransitActionController, Set<String>>(
        TransitActionController.new);
