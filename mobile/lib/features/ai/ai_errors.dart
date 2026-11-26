import '../../core/network/api_failure.dart';
import 'ai_models.dart';

/// Nhãn nguồn: không được trình bày kết quả giả lập/dự phòng/công thức như AI thật.
enum AiSourceTone { ai, mock, fallback, formula }

class AiSourceInfo {
  const AiSourceInfo(this.tone, this.label, this.detail);
  final AiSourceTone tone;
  final String label, detail;

  /// Chỉ true khi PROVIDER, không mock, không degraded.
  bool get isRealAi => tone == AiSourceTone.ai;
}

/// Thông báo theo mã lỗi `AI_*` của backend (không chứa chi tiết nhà cung cấp).
const aiErrorMessages = <String, String>{
  'AI_NOT_CONFIGURED': 'Dịch vụ AI chưa được cấu hình hoặc đang tắt.',
  'AI_PROVIDER_UNAVAILABLE': 'Dịch vụ AI tạm thời không khả dụng.',
  'AI_TIMEOUT': 'Dịch vụ AI phản hồi quá chậm.',
  'AI_RATE_LIMITED': 'Đã vượt giới hạn số lần dùng AI. Vui lòng thử lại sau.',
  'AI_BUDGET_EXCEEDED': 'Hệ thống đã hết hạn mức AI trong ngày.',
  'AI_RESPONSE_INVALID': 'Phản hồi AI không hợp lệ nên đã bị chặn.',
};

AiSourceInfo describeAiSource(AiEnvelope<Object?> env) {
  if (env.source == AiSource.formula) {
    return const AiSourceInfo(AiSourceTone.formula, 'Ước tính theo công thức',
        'Không phải AI hay mô hình học máy; tham số chưa hiệu chuẩn.');
  }
  if (env.source == AiSource.mock || env.mock) {
    return const AiSourceInfo(AiSourceTone.mock, 'Giả lập (MOCK)',
        'Nội dung giả lập để thử nghiệm, không phải kết quả AI thật.');
  }
  if (env.source == AiSource.fallback || env.degraded) {
    final reason = aiErrorMessages[env.fallbackReason];
    return AiSourceInfo(
        AiSourceTone.fallback,
        'Dự phòng (không dùng AI)',
        reason == null
            ? 'AI không khả dụng. Nội dung do hệ thống tạo theo quy tắc cố định.'
            : 'AI không khả dụng: $reason Nội dung do hệ thống tạo theo quy tắc cố định.');
  }
  // Nguồn lạ (unknown) không được gắn nhãn AI thật.
  if (env.source != AiSource.provider) {
    return const AiSourceInfo(AiSourceTone.fallback, 'Không rõ nguồn',
        'Không xác định được nguồn kết quả nên không coi là AI thật.');
  }
  return AiSourceInfo(
      AiSourceTone.ai,
      env.model == null ? 'AI' : 'AI (${env.model})',
      'Do mô hình AI tạo và đã qua kiểm tra của hệ thống; vẫn nên đối chiếu với dữ liệu đơn.');
}

/// Yêu cầu bị hủy chủ động (rời màn hình, đổi đơn, đăng xuất). Không phải lỗi, màn hình bỏ qua.
class AiCanceled implements Exception {
  const AiCanceled();
}

/// Số lần thất bại liên tiếp tối đa trước khi ngừng đề nghị "Thử lại" (tránh tốn hạn mức AI).
const maxAiFailures = 3;

/// Thông tin lỗi để màn hình hiển thị và quyết định có cho thử lại không.
class AiErrorInfo {
  const AiErrorInfo(this.failure, this.message, {required this.retryable});
  final ApiFailure failure;
  final String message;

  /// Có nên đề nghị "Thử lại". Màn hình vẫn tự giới hạn số lần ([maxAiFailures]).
  final bool retryable;

  /// Chat: hội thoại đang xử lý (409), đợi rồi thử lại.
  bool get busy => failure.status == 409;

  /// Chat: hội thoại hết hạn/không tồn tại (404) hoặc đã đủ lượt/đổi đơn (400): cần bắt đầu lại.
  bool get needsNewConversation =>
      failure.kind == FailureKind.notFound ||
      failure.kind == FailureKind.validation;

  /// Mạng chập chờn: yêu cầu có thể đã được xử lý; gửi lại chat có thể tạo thêm một lượt.
  bool get outcomeUnknown =>
      failure.kind == FailureKind.timeout || failure.kind == FailureKind.offline;
}

/// 429 của AI (rate limit/ngân sách) và AI_NOT_CONFIGURED không được thử lại: chỉ tốn thêm hạn mức.
/// Không dùng [ApiFailure.isRetryable] vì nó coi mọi 429 là thử lại được.
AiErrorInfo describeAiFailure(Object error) {
  final f = toApiFailure(error);
  final aiMessage = aiErrorMessages[f.code];
  if (f.kind == FailureKind.rateLimited) {
    return AiErrorInfo(f, aiMessage ?? f.message, retryable: false);
  }
  if (aiMessage != null) {
    return AiErrorInfo(f, aiMessage, retryable: f.code != 'AI_NOT_CONFIGURED');
  }
  if (f.status == 409) {
    return AiErrorInfo(
        f, 'Yêu cầu trước đang được xử lý. Vui lòng đợi giây lát rồi thử lại.',
        retryable: true);
  }
  if (f.kind == FailureKind.forbidden) {
    return AiErrorInfo(f, 'Tài khoản không có quyền dùng tính năng này.',
        retryable: false);
  }
  if (f.kind == FailureKind.notFound) {
    return AiErrorInfo(f,
        'Không tìm thấy dữ liệu, dữ liệu đã hết hạn hoặc bạn không có quyền xem.',
        retryable: false);
  }
  return AiErrorInfo(f, f.message, retryable: f.isRetryable);
}
