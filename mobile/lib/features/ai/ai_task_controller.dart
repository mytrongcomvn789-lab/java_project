import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import '../../core/realtime/realtime_provider.dart';
import 'ai_api.dart';
import 'ai_errors.dart';
import 'ai_models.dart';

/// Khóa của một tác vụ AI trên một đơn. Đổi trạng thái hoặc phiên bản đơn thì ra khóa mới nên
/// kết quả cũ bị bỏ và yêu cầu cũ bị hủy (provider autoDispose).
class AiOrderKey {
  const AiOrderKey(this.orderId, this.status, this.version);
  final String orderId, status;
  final int version;

  @override
  bool operator ==(Object other) =>
      other is AiOrderKey &&
      other.orderId == orderId &&
      other.status == status &&
      other.version == version;

  @override
  int get hashCode => Object.hash(orderId, status, version);
}

enum AiPhase { idle, loading, success, error }

class AiTaskState<T> {
  const AiTaskState(this.phase, {this.envelope, this.error, this.failures = 0});
  final AiPhase phase;
  final AiEnvelope<T>? envelope;
  final AiErrorInfo? error;

  /// Số lần thất bại liên tiếp của lượt chạy hiện tại.
  final int failures;

  /// Còn được đề nghị "Thử lại": lỗi tạm thời và chưa vượt [maxAiFailures].
  bool get canRetry =>
      phase == AiPhase.error &&
      (error?.retryable ?? false) &&
      failures < maxAiFailures;
}

/// Chạy một tác vụ AI theo đơn: hủy khi rời màn hình/đổi đơn, bỏ phản hồi muộn của lượt cũ,
/// xóa dữ liệu khi đăng xuất hoặc đổi tài khoản, thử lại do người dùng bấm và có giới hạn.
abstract class AiTaskController<T>
    extends AutoDisposeFamilyNotifier<AiTaskState<T>, AiOrderKey> {
  /// true: tự chạy khi mở (chỉ cho tác vụ không tốn token như ETA).
  bool get auto;

  Future<AiEnvelope<T>> run(AiApi api, String orderId, CancelToken cancel);

  CancelToken? _token;
  int _failures = 0;

  @override
  AiTaskState<T> build(AiOrderKey arg) {
    // Đăng xuất/đổi tài khoản: provider dựng lại, dữ liệu của tài khoản cũ bị xóa.
    ref.watch(realtimeUserProvider);
    ref.onDispose(() => _token?.cancel());
    if (auto) {
      Future.microtask(start);
      return const AiTaskState(AiPhase.loading);
    }
    return const AiTaskState(AiPhase.idle);
  }

  Future<void> start() async {
    _token?.cancel();
    final token = CancelToken();
    _token = token;
    state = AiTaskState(AiPhase.loading, failures: _failures);
    try {
      final env = await run(ref.read(aiApiProvider), arg.orderId, token);
      if (_token != token) return;
      _failures = 0;
      state = AiTaskState(AiPhase.success, envelope: env);
    } on AiCanceled {
      // Hủy chủ động: không phải lỗi.
    } catch (e) {
      if (_token != token) return;
      _failures++;
      state = AiTaskState(AiPhase.error,
          error: describeAiFailure(e), failures: _failures);
    }
  }
}

/// ETA (7.2): công thức, tự tải.
class EtaController extends AiTaskController<EtaResult> {
  @override
  bool get auto => true;
  @override
  Future<AiEnvelope<EtaResult>> run(AiApi api, String orderId, CancelToken c) =>
      api.eta(orderId, cancel: c);
}

/// Tóm tắt (7.3): chỉ chạy khi người dùng bấm vì có thể tốn hạn mức AI.
class SummaryController extends AiTaskController<OrderSummaryResult> {
  @override
  bool get auto => false;
  @override
  Future<AiEnvelope<OrderSummaryResult>> run(
          AiApi api, String orderId, CancelToken c) =>
      api.summary(orderId, cancel: c);
}

final etaProvider = NotifierProvider.autoDispose
    .family<EtaController, AiTaskState<EtaResult>, AiOrderKey>(
        EtaController.new);

final summaryProvider = NotifierProvider.autoDispose
    .family<SummaryController, AiTaskState<OrderSummaryResult>, AiOrderKey>(
        SummaryController.new);
