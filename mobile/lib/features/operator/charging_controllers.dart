import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import 'charging_api.dart';
import 'operator_controllers.dart';

/// Chi tiết sạc và drone của MỘT trạm. Gắn với id nhân viên: đăng xuất hoặc đổi tài khoản thì bỏ dữ liệu.
final stationChargersProvider =
    FutureProvider.autoDispose.family<StationChargers, String>((ref, stationId) {
  ref.watch(operatorUserProvider);
  return ref.read(chargingApiProvider).stationChargers(stationId);
});

/// Bảng điều phối drone: đội drone và các đơn cần hoặc đã có drone trong phạm vi trạm.
final dispatchBoardProvider = FutureProvider.autoDispose<DispatchBoard>((ref) {
  ref.watch(operatorUserProvider);
  return ref.read(chargingApiProvider).board();
});

/// Kết quả một thao tác (cho drone rời, bảo trì, phân công).
class ChargingActionResult {
  const ChargingActionResult(this.ok, this.message);
  final bool ok;
  final String message;
}

/// Các thao tác ghi. Mỗi thao tác chặn bấm đúp theo khóa (mã thiết bị hoặc mã đơn); server là nơi quyết
/// định hợp lệ (drone chưa đầy hoặc thiết bị còn drone thì bị từ chối kèm lời giải thích tiếng Việt).
class ChargingActions extends AutoDisposeNotifier<Set<String>> {
  @override
  Set<String> build() => const {};

  ChargingApi get _api => ref.read(chargingApiProvider);

  Future<ChargingActionResult> _run(
      String key, Future<void> Function() call, String done, {String? stationId}) async {
    if (state.contains(key)) return const ChargingActionResult(false, '');
    state = {...state, key};
    try {
      await call();
      if (stationId != null) ref.invalidate(stationChargersProvider(stationId));
      ref.invalidate(dispatchBoardProvider);
      return ChargingActionResult(true, done);
    } catch (e) {
      final f = toApiFailure(e);
      // Dữ liệu đã cũ (thiết bị vừa đổi trạng thái): tải lại để người dùng thấy sự thật.
      if (stationId != null) ref.invalidate(stationChargersProvider(stationId));
      return ChargingActionResult(false, f.outcomeUnknown
          ? 'Chưa rõ thao tác đã được thực hiện chưa. Đã tải lại để kiểm tra.'
          : f.message);
    } finally {
      state = {...state}..remove(key);
    }
  }

  Future<ChargingActionResult> release(String stationId, String chargerId, String code) => _run(
      'release-$chargerId', () => _api.release(stationId, chargerId), 'Drone đã rời thiết bị $code.',
      stationId: stationId);

  Future<ChargingActionResult> setMaintenance(
          String stationId, String chargerId, String code, bool maintenance) =>
      _run('maint-$chargerId', () => _api.setMaintenance(stationId, chargerId, maintenance),
          maintenance ? 'Thiết bị $code đã chuyển sang bảo trì.' : 'Thiết bị $code hoạt động trở lại.',
          stationId: stationId);

  Future<ChargingActionResult> assign(String orderId) =>
      _run('assign-$orderId', () => _api.assign(orderId), 'Đã tính lại kế hoạch drone cho đơn.');
}

final chargingActionsProvider =
    NotifierProvider.autoDispose<ChargingActions, Set<String>>(ChargingActions.new);
