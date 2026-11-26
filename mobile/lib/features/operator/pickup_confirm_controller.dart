import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/network/api_failure.dart';
import '../orders/customer_order_models.dart' show OrderDetail;
import 'confirmation_photo_controller.dart';
import 'operator_api.dart';
import 'operator_controllers.dart';
import 'operator_models.dart';
import 'operator_sync.dart';

class PickupConfirmState {
  const PickupConfirmState(
      {this.order,
      this.loading = true,
      this.error,
      this.credential,
      this.submitting = false,
      this.result,
      this.failure,
      this.notice,
      this.queued = false});
  final OrderDetail? order;
  final bool loading, submitting;

  /// Mất mạng nên yêu cầu xác nhận (kèm mã và ảnh đã tải) được lưu và sẽ tự gửi lại: đơn CHƯA
  /// được giao trên server cho tới khi hàng đợi gửi thành công.
  final bool queued;

  /// Lỗi tải đơn khi chưa có dữ liệu để hiện.
  final ApiFailure? error;

  /// Mã đã quét hoặc đã nhập (đúng một loại). Không bao giờ được ghi log.
  final PickupCredential? credential;

  /// Kết quả khi server đã xác nhận giao (2xx). Chỉ có giá trị này mới hiện "đã giao".
  final ConfirmResult? result;

  /// Lỗi của lần xác nhận gần nhất.
  final ApiFailure? failure;

  /// Thông báo sau khi dữ liệu bị cũ và đã tải lại.
  final String? notice;

  /// Đơn còn chờ khách nhận (chỉ để quyết định hiện form; server vẫn kiểm tra).
  bool get awaitingPickup =>
      order?.status == 'AWAITING_PICKUP' && result == null && !queued;

  PickupConfirmState copyWith(
          {OrderDetail? order,
          bool? loading,
          bool? submitting,
          bool? queued,
          ApiFailure? error,
          PickupCredential? credential,
          ConfirmResult? result,
          ApiFailure? failure,
          String? notice,
          bool clearError = false,
          bool clearCredential = false,
          bool clearFailure = false,
          bool clearNotice = false}) =>
      PickupConfirmState(
          order: order ?? this.order,
          loading: loading ?? this.loading,
          submitting: submitting ?? this.submitting,
          error: clearError ? null : error ?? this.error,
          credential: clearCredential ? null : credential ?? this.credential,
          result: result ?? this.result,
          failure: clearFailure ? null : failure ?? this.failure,
          notice: clearNotice ? null : notice ?? this.notice,
          queued: queued ?? this.queued);
}

/// Xác nhận giao hàng cho khách (4.7). Server xác minh mã, ảnh, quyền ở trạm nhận và tự chuyển
/// trạng thái sang DELIVERED; app chỉ gửi yêu cầu và hiển thị kết quả server trả về, không tự
/// đánh dấu đã giao.
class PickupConfirmController
    extends AutoDisposeFamilyNotifier<PickupConfirmState, String> {
  int _generation = 0;

  @override
  PickupConfirmState build(String orderId) {
    ref.watch(operatorUserProvider);
    ref.onDispose(() => _generation++);
    Future.microtask(reload);
    return const PickupConfirmState();
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
      state = state.order == null
          ? state.copyWith(loading: false, error: failure)
          : state.copyWith(loading: false, failure: silent ? null : failure);
    }
  }

  void setCredential(PickupCredential? c) => state = c == null
      ? state.copyWith(clearCredential: true, clearFailure: true)
      : state.copyWith(credential: c, clearFailure: true);

  void dismissMessages() =>
      state = state.copyWith(clearNotice: true, clearFailure: true);

  /// Gửi xác nhận. Trả true chỉ khi server xác nhận (2xx). Gọi lại khi đang gửi bị bỏ qua.
  ///
  /// Gửi lại sau lỗi mạng hoặc hết thời gian chờ dùng ĐÚNG yêu cầu cũ (cùng phiên bản, ảnh,
  /// mã): nếu lần trước server đã xử lý thì trả lại kết quả cũ (`replayed`), không giao hai lần.
  Future<bool> confirm(String? note) async {
    final order = state.order;
    final credential = state.credential;
    final photoId = ref.read(confirmationPhotoProvider(arg)).fileId; // chỉ có khi upload xong
    if (order == null ||
        credential == null ||
        photoId == null ||
        state.submitting ||
        !state.awaitingPickup) {
      return false;
    }
    state = state.copyWith(submitting: true, clearFailure: true, clearNotice: true);
    final request = ConfirmRequest(
        orderId: order.id,
        version: order.version,
        photoFileId: photoId,
        credential: credential,
        note: note);
    try {
      final result = await _api.confirm(request);
      _generation++; // bỏ phản hồi tải lại đang chờ (nếu có)
      ref.invalidate(pickupListProvider); // đơn rời danh sách chờ giao
      state = state.copyWith(submitting: false, result: result);
      return true;
    } catch (e) {
      final failure = toApiFailure(e);
      if (failure.kind == FailureKind.offline || failure.outcomeUnknown) {
        // Mất mạng (6.5): lưu yêu cầu vào hàng đợi mã hóa với ĐÚNG phiên bản, ảnh và mã này.
        // Gửi lại y nguyên là an toàn vì server trả lại kết quả cũ nếu lần trước đã xử lý.
        final user = ref.read(operatorUserProvider);
        final saved = user != null &&
            await ref
                .read(operatorSyncProvider.notifier)
                .enqueue(pendingConfirm(user, request));
        state = saved
            ? state.copyWith(
                submitting: false, queued: true, clearCredential: true)
            : state.copyWith(submitting: false, failure: failure);
      } else if (failure.isPickupCodeProblem) {
        // Mã không dùng được: bỏ mã để nhập hoặc quét lại (giữ ảnh đã tải).
        state = state.copyWith(
            submitting: false, failure: failure, clearCredential: true);
      } else if (failure.isStale) {
        // Phiên bản cũ / trạng thái đã đổi: tải lại bản thật, giữ mã và ảnh.
        state = state.copyWith(
            submitting: false,
            notice: 'Đơn vừa thay đổi. Đã tải lại trạng thái mới nhất.');
        await reload(silent: true);
      } else if (failure.kind == FailureKind.notFound) {
        state = state.copyWith(
            submitting: false,
            failure: const ApiFailure(FailureKind.notFound,
                'Không tìm thấy đơn hoặc ảnh xác nhận, hoặc bạn không phải nhân viên trạm nhận của đơn này.'));
      } else {
        // Mất mạng, hết thời gian chờ, máy chủ lỗi…: giữ nguyên mã, ảnh, phiên bản để gửi lại.
        state = state.copyWith(submitting: false, failure: failure);
      }
      return false;
    }
  }
}

final pickupConfirmProvider = NotifierProvider.autoDispose
    .family<PickupConfirmController, PickupConfirmState, String>(
        PickupConfirmController.new);
