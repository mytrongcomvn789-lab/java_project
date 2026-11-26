import 'dart:async';
import 'dart:math';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../../core/offline/pending_action.dart';
import '../orders/customer_order_models.dart' show OrderDetail;
import 'operator_api.dart';
import 'operator_controllers.dart';
import 'operator_models.dart';
import 'return_controller.dart' show returnListProvider;

/// Nơi lưu hàng đợi. Test thay bằng bộ nhớ trong.
final pendingStoreProvider =
    Provider<PendingStore>((ref) => const SecurePendingStore());

/// Tắt trong test để không có timer treo; bản thật luôn tự thử lại định kỳ.
final syncAutoRetryProvider = Provider<bool>((ref) => true);

/// Thời gian chờ trước lần thử lại thứ [streak] (tăng gấp đôi, tối đa 5 phút).
Duration syncRetryDelay(int streak) {
  final seconds = min(15 * pow(2, streak).toInt(), 300);
  return Duration(seconds: seconds);
}

// ── Chuyển giữa request và thao tác trong hàng đợi ──────────────────────────────────

PendingAction _newAction(String userId, PendingKind kind, String orderId,
        Map<String, dynamic> payload) =>
    PendingAction(
        id: newIdempotencyKey(),
        userId: userId,
        kind: kind,
        orderId: orderId,
        payload: payload,
        createdAt: DateTime.now());

PendingAction pendingReceive(String userId, ReceiveRequest r) =>
    _newAction(userId, PendingKind.receive, r.orderId, {
      'version': r.version,
      'attemptId': r.attemptId,
      'packageId': r.packageId,
      if (r.note != null) 'note': r.note,
    });

ReceiveRequest receiveOf(PendingAction a) => ReceiveRequest(
    orderId: a.orderId,
    version: (a.payload['version'] as num).toInt(),
    attemptId: a.payload['attemptId'] as String,
    packageId: a.payload['packageId'] as String,
    note: a.payload['note'] as String?);

/// Yêu cầu xác nhận giao giữ NGUYÊN version, ảnh và mã: nhờ vậy gửi lại là an toàn (server trả
/// lại kết quả cũ nếu lần trước đã xử lý).
PendingAction pendingConfirm(String userId, ConfirmRequest r) =>
    _newAction(userId, PendingKind.confirm, r.orderId, {
      'version': r.version,
      'photoFileId': r.photoFileId,
      ...r.credential.toBody(),
      if (r.note != null) 'note': r.note,
    });

ConfirmRequest confirmOf(PendingAction a) {
  final p = a.payload;
  final credential = p['otp'] is String
      ? PickupCredential.otp(p['otp'] as String)
      : PickupCredential.qr(p['qrToken'] as String);
  return ConfirmRequest(
      orderId: a.orderId,
      version: (p['version'] as num).toInt(),
      photoFileId: p['photoFileId'] as String,
      credential: credential,
      note: p['note'] as String?);
}

PendingAction pendingReturn(String userId, CompleteReturnRequest r) =>
    _newAction(userId, PendingKind.completeReturn, r.orderId, {
      'version': r.version,
      if (r.note != null) 'note': r.note,
    });

CompleteReturnRequest returnOf(PendingAction a) => CompleteReturnRequest(
    orderId: a.orderId,
    version: (a.payload['version'] as num).toInt(),
    note: a.payload['note'] as String?);

// ── Trạng thái ──────────────────────────────────────────────────────────────────────

class SyncState {
  const SyncState(
      {this.actions = const [], this.syncing = false, this.loaded = false});
  final List<PendingAction> actions;
  final bool syncing, loaded;

  int get waiting =>
      actions.where((a) => a.status == PendingStatus.waiting).length;
  int get needsAttention =>
      actions.where((a) => a.status == PendingStatus.needsAttention).length;
  bool get isEmpty => actions.isEmpty;

  bool hasPending(String orderId, PendingKind kind) =>
      actions.any((a) => a.orderId == orderId && a.kind == kind);

  SyncState copyWith(
          {List<PendingAction>? actions, bool? syncing, bool? loaded}) =>
      SyncState(
          actions: actions ?? this.actions,
          syncing: syncing ?? this.syncing,
          loaded: loaded ?? this.loaded);
}

sealed class _Outcome {
  const _Outcome();
}

/// Server đã xử lý (hoặc đơn đã ở trạng thái đích): bỏ khỏi hàng đợi.
class _Done extends _Outcome {
  const _Done();
}

/// Lỗi tạm thời (mất mạng, hết thời gian chờ, máy chủ lỗi): giữ lại và dừng đợt này.
class _Stop extends _Outcome {
  const _Stop();
}

/// Server từ chối hoặc đơn đã đổi: giữ lại kèm lý do để người dùng quyết định.
class _Attention extends _Outcome {
  const _Attention(this.message);
  final String message;
}

/// Hàng đợi thao tác vận hành khi mất mạng (6.5). Thao tác nào cũng đi qua đúng API của 6.4 và
/// 4.8; app không tự quyết định nghiệp vụ, server vẫn kiểm tra quyền, trạng thái và phiên bản.
///
/// Cách gửi lại khác nhau theo tính chất của từng thao tác:
/// - Xác nhận giao: gửi lại y nguyên (server trả lại kết quả cũ nếu đã xử lý).
/// - Nhận kiện, nhận hàng hoàn: KHÔNG replay được, nên mỗi lần gửi lại phải tải đơn trước, chỉ
///   gửi khi đơn vẫn đúng trạng thái và đúng phiên bản lúc xếp hàng.
class OperatorSyncController extends Notifier<SyncState> {
  Timer? _timer;
  bool _busy = false;
  int _streak = 0;
  int _generation = 0;
  String? _userId;

  @override
  SyncState build() {
    _userId = ref.watch(operatorUserProvider);
    final gen = ++_generation;
    ref.onDispose(() {
      _generation++;
      _timer?.cancel();
    });
    _timer?.cancel();
    _busy = false;
    _streak = 0;
    _loading = _userId == null ? null : Future.microtask(() => _load(gen));
    return const SyncState();
  }

  /// Việc đọc hàng đợi đã lưu khi mở app. Mọi thao tác ghi phải chờ xong để không ghi đè.
  Future<void>? _loading;

  OperatorApi get _api => ref.read(operatorApiProvider);
  PendingStore get _store => ref.read(pendingStoreProvider);

  Future<void> _load(int gen) async {
    final user = _userId;
    if (user == null) return;
    try {
      final saved = await _store.read(user);
      if (gen != _generation) return;
      state = SyncState(actions: saved, loaded: true);
    } catch (_) {
      if (gen == _generation) state = const SyncState(loaded: true);
      return;
    }
    if (state.waiting > 0) unawaited(syncNow());
  }

  Future<bool> _save(List<PendingAction> actions) async {
    final user = _userId;
    if (user == null) return false;
    try {
      await _store.write(user, actions);
      return true;
    } catch (_) {
      return false;
    }
  }

  /// Xếp một thao tác vào hàng đợi. Trả false nếu không lưu được (khi đó màn hình phải báo
  /// thao tác CHƯA được thực hiện như bình thường). Cùng đơn và cùng loại thì không xếp thêm.
  Future<bool> enqueue(PendingAction action) async {
    if (_userId == null || action.userId != _userId) return false;
    final gen = _generation;
    await _loading;
    if (gen != _generation) return false;
    bool same(PendingAction a) =>
        a.orderId == action.orderId && a.kind == action.kind;
    // Đang có bản chờ gửi cho cùng đơn và loại thì không xếp thêm (tránh gửi hai lần). Bản đã
    // bị server từ chối thì được thay bằng yêu cầu mới của người dùng.
    if (state.actions
        .any((a) => same(a) && a.status == PendingStatus.waiting)) {
      return true;
    }
    final next = [
      for (final a in state.actions)
        if (!same(a)) a,
      action
    ];
    if (!await _save(next)) return false;
    state = state.copyWith(actions: next);
    _schedule();
    return true;
  }

  /// Bỏ một thao tác khỏi hàng đợi (người dùng chọn bỏ).
  Future<void> discard(String id) async {
    final next = [for (final a in state.actions) if (a.id != id) a];
    state = state.copyWith(actions: next);
    await _save(next);
  }

  void _schedule() {
    _timer?.cancel();
    if (!ref.read(syncAutoRetryProvider)) return;
    if (state.waiting == 0) return;
    _timer = Timer(syncRetryDelay(_streak), () => unawaited(syncNow()));
  }

  /// Gửi lần lượt các thao tác đang chờ. Gọi lại khi đang chạy bị bỏ qua.
  Future<void> syncNow() async {
    if (_busy || _userId == null) return;
    _busy = true;
    final gen = _generation;
    await _loading;
    if (gen != _generation) return;
    _timer?.cancel();
    state = state.copyWith(syncing: true);
    var hitTemporaryError = false;
    var changedOrders = false;
    try {
      for (final a in List.of(state.actions)) {
        if (a.status != PendingStatus.waiting) continue;
        final outcome = await _run(a);
        if (gen != _generation) return;
        switch (outcome) {
          case _Done():
            changedOrders = true;
            _replace(a.id, null);
          case _Attention(:final message):
            _replace(
                a.id,
                a.copyWith(
                    status: PendingStatus.needsAttention, message: message));
          case _Stop():
            _replace(a.id, a.copyWith(attempts: a.attempts + 1));
            hitTemporaryError = true;
        }
        if (hitTemporaryError) break; // vẫn mất mạng: không thử tiếp các việc sau
      }
    } finally {
      if (gen == _generation) {
        _busy = false;
        await _save(state.actions);
        state = state.copyWith(syncing: false);
        _streak = hitTemporaryError ? _streak + 1 : 0;
        if (changedOrders) {
          ref.invalidate(receiptListProvider);
          ref.invalidate(pickupListProvider);
          ref.invalidate(returnListProvider);
        }
        _schedule();
      }
    }
  }

  void _replace(String id, PendingAction? replacement) {
    state = state.copyWith(actions: [
      for (final a in state.actions)
        if (a.id != id)
          a
        else if (replacement != null)
          replacement
    ]);
  }

  Future<_Outcome> _run(PendingAction a) async {
    try {
      switch (a.kind) {
        case PendingKind.confirm:
          return await _runConfirm(a);
        case PendingKind.receive:
          return await _runGuarded(
              a,
              expectedStatus: 'SCHEDULED',
              doneStatus: 'PREPARING',
              send: (_) => _api.receive(receiveOf(a)));
        case PendingKind.completeReturn:
          return await _runGuarded(
              a,
              expectedStatus: 'RETURNING',
              doneStatus: 'RETURNED',
              send: (_) => _api.completeReturn(returnOf(a)));
      }
    } catch (e) {
      final f = toApiFailure(e);
      if (_keepWaiting(f)) return const _Stop();
      return _Attention(f.message);
    }
  }

  /// Lỗi mà thử lại sau có thể thành công mà người dùng không cần làm gì.
  bool _keepWaiting(ApiFailure f) =>
      f.isRetryable || f.kind == FailureKind.unauthorized;

  Future<_Outcome> _runConfirm(PendingAction a) async {
    try {
      await _api.confirm(confirmOf(a));
      return const _Done();
    } catch (e) {
      final f = toApiFailure(e);
      if (_keepWaiting(f)) return const _Stop();
      if (f.isPickupCodeProblem) {
        return _Attention('${f.message} Mở lại đơn, nhờ khách mở mã mới rồi xác nhận lại.');
      }
      // Đơn đã đổi hoặc không còn trong phạm vi: nếu thực ra đã được giao thì không cần làm gì.
      try {
        final o = await _api.orderDetail(a.orderId);
        if (o.status == 'DELIVERED') return const _Done();
      } catch (e2) {
        if (_keepWaiting(toApiFailure(e2))) return const _Stop();
      }
      return _Attention(f.message);
    }
  }

  /// Nhận kiện và nhận hàng hoàn: tải đơn trước rồi chỉ gửi khi đơn vẫn đúng như lúc xếp hàng.
  Future<_Outcome> _runGuarded(PendingAction a,
      {required String expectedStatus,
      required String doneStatus,
      required Future<OrderDetail> Function(OrderDetail) send}) async {
    final queuedVersion = (a.payload['version'] as num).toInt();
    final OrderDetail order;
    try {
      order = await _api.orderDetail(a.orderId);
    } catch (e) {
      final f = toApiFailure(e);
      if (_keepWaiting(f)) return const _Stop();
      return _Attention(f.message);
    }
    if (order.history
        .any((h) => h.toStatus == doneStatus && h.orderVersion > queuedVersion)) {
      return const _Done(); // đã được thực hiện (có thể chính lần gửi trước đã tới server)
    }
    if (order.status != expectedStatus) {
      return _Attention(
          'Đơn hiện ở trạng thái "${order.statusLabel}" nên không thể gửi yêu cầu này nữa.');
    }
    if (order.version != queuedVersion) {
      return const _Attention(
          'Đơn đã thay đổi sau khi bạn thao tác. Mở lại đơn để kiểm tra rồi làm lại.');
    }
    try {
      await send(order);
      return const _Done();
    } catch (e) {
      final f = toApiFailure(e);
      if (_keepWaiting(f)) return const _Stop();
      return _Attention(f.message);
    }
  }
}

final operatorSyncProvider =
    NotifierProvider<OperatorSyncController, SyncState>(
        OperatorSyncController.new);
