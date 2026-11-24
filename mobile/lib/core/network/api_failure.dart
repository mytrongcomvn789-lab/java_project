import 'dart:math';
import 'package:dio/dio.dart';

/// Loại lỗi mà màn hình cần phân biệt để xử lý (không chỉ hiện một câu chung).
enum FailureKind {
  /// Không tới được máy chủ: chắc chắn yêu cầu CHƯA được xử lý.
  offline,

  /// Hết thời gian chờ sau khi đã gửi: KHÔNG biết server đã xử lý hay chưa.
  timeout,
  validation, // 400
  unauthorized, // 401
  forbidden, // 403
  notFound, // 404
  stateConflict, // 409 INVALID_STATE_TRANSITION: trạng thái đơn không cho phép
  dataConflict, // 409 khác
  idempotencyConflict, // 409 IDEMPOTENCY_KEY_CONFLICT
  versionConflict, // 412: dữ liệu đã cũ
  preconditionRequired, // 428
  fileTooLarge, // 413
  fileTypeNotAllowed, // 415
  rateLimited, // 429
  pickupCodeInvalid, // 422 PICKUP_CODE_INVALID: mã OTP/QR không đúng
  pickupCodeExpired, // 410 PICKUP_CODE_EXPIRED: khách cần mở lại mã
  pickupCodeLocked, // 429 PICKUP_CODE_LOCKED: nhập sai quá số lần
  pickupCodeUsed, // 409 PICKUP_CODE_USED
  storageUnavailable, // 503 STORAGE_UNAVAILABLE
  server, // 5xx còn lại
  unknown,
}

/// Lỗi API đã phân loại. `message` là câu tiếng Việt hiển thị cho người dùng.
class ApiFailure implements Exception {
  const ApiFailure(this.kind, this.message, {this.status, this.code});
  final FailureKind kind;
  final String message;
  final int? status;
  final String? code;

  /// Dữ liệu đang xem đã cũ: cần tải lại trước khi thử lại.
  bool get isStale =>
      kind == FailureKind.versionConflict || kind == FailureKind.stateConflict;

  /// Không chắc server đã nhận yêu cầu hay chưa (chỉ gặp khi hết thời gian chờ).
  bool get outcomeUnknown => kind == FailureKind.timeout;

  /// Lỗi tạm thời: gửi lại cùng yêu cầu có thể thành công mà không cần người dùng sửa gì
  /// (mất mạng, hết thời gian chờ, máy chủ/kho ảnh lỗi, quá nhiều yêu cầu). Mã nhận hàng
  /// bị khóa (429 PICKUP_CODE_LOCKED) KHÔNG nằm trong nhóm này. Dùng cho hàng đợi retry của 6.5.
  bool get isRetryable => switch (kind) {
        FailureKind.offline ||
        FailureKind.timeout ||
        FailureKind.server ||
        FailureKind.storageUnavailable ||
        FailureKind.rateLimited =>
          true,
        _ => false,
      };

  /// Mã nhận hàng không dùng được: người dùng phải nhập lại hoặc nhờ khách mở lại mã.
  bool get isPickupCodeProblem =>
      kind == FailureKind.pickupCodeInvalid ||
      kind == FailureKind.pickupCodeExpired ||
      kind == FailureKind.pickupCodeLocked ||
      kind == FailureKind.pickupCodeUsed;

  @override
  String toString() => 'ApiFailure($kind, $status, $code, $message)';
}

/// Đổi mọi lỗi (thường là [DioException]) thành [ApiFailure]. Đã là
/// [ApiFailure] thì giữ nguyên.
ApiFailure toApiFailure(Object error) {
  if (error is ApiFailure) return error;
  if (error is! DioException) {
    return const ApiFailure(
        FailureKind.unknown, 'Không thể hoàn tất thao tác. Vui lòng thử lại.');
  }
  final status = error.response?.statusCode;
  final data = error.response?.data;
  final code = data is Map && data['code'] is String ? data['code'] as String : null;
  final serverMessage =
      data is Map && data['message'] is String ? data['message'] as String : null;

  if (status == null) {
    switch (error.type) {
      case DioExceptionType.connectionError:
      case DioExceptionType.connectionTimeout:
        return const ApiFailure(FailureKind.offline,
            'Không kết nối được máy chủ. Kiểm tra mạng và thử lại.');
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
        return const ApiFailure(FailureKind.timeout,
            'Máy chủ phản hồi quá lâu nên chưa rõ thao tác đã thực hiện chưa. Hãy kiểm tra lại danh sách đơn.');
      default:
        return const ApiFailure(FailureKind.unknown,
            'Không thể hoàn tất thao tác. Vui lòng thử lại.');
    }
  }

  ApiFailure make(FailureKind kind, String fallback) => ApiFailure(
      kind, serverMessage ?? fallback,
      status: status, code: code);

  switch (status) {
    case 400:
      return make(FailureKind.validation, 'Dữ liệu không hợp lệ.');
    case 401:
      return make(FailureKind.unauthorized,
          'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
    case 403:
      return make(FailureKind.forbidden,
          'Tài khoản không có quyền thực hiện thao tác này.');
    case 404:
      return make(FailureKind.notFound,
          'Không tìm thấy dữ liệu hoặc bạn không có quyền xem.');
    case 409:
      if (code == 'PICKUP_CODE_USED') {
        return make(FailureKind.pickupCodeUsed,
            'Mã nhận hàng đã được sử dụng.');
      }
      if (code == 'INVALID_STATE_TRANSITION') {
        return make(FailureKind.stateConflict,
            'Đơn không còn ở trạng thái cho phép thao tác này.');
      }
      if (code == 'IDEMPOTENCY_KEY_CONFLICT') {
        return make(FailureKind.idempotencyConflict,
            'Yêu cầu trùng mã nhưng nội dung khác. Vui lòng gửi lại.');
      }
      return make(FailureKind.dataConflict,
          'Dữ liệu vừa thay đổi. Vui lòng tải lại và thử lại.');
    case 412:
      return make(FailureKind.versionConflict,
          'Đơn vừa được cập nhật. Vui lòng tải lại.');
    case 410:
      return make(FailureKind.pickupCodeExpired,
          'Mã nhận hàng đã hết hạn. Nhờ khách mở lại mã.');
    case 422:
      return make(FailureKind.pickupCodeInvalid,
          'Mã nhận hàng không đúng hoặc không còn hiệu lực.');
    case 413:
      return make(FailureKind.fileTooLarge, 'Ảnh vượt quá dung lượng cho phép.');
    case 415:
      return make(FailureKind.fileTypeNotAllowed, 'Loại ảnh không được hỗ trợ.');
    case 428:
      return make(FailureKind.preconditionRequired,
          'Thiếu thông tin phiên bản. Vui lòng tải lại.');
    case 429:
      if (code == 'PICKUP_CODE_LOCKED') {
        return make(FailureKind.pickupCodeLocked,
            'Mã nhận hàng đã bị khóa do nhập sai quá số lần. Nhờ khách mở lại mã.');
      }
      return make(FailureKind.rateLimited,
          'Quá nhiều yêu cầu. Vui lòng thử lại sau.');
    case 503:
      if (code == 'STORAGE_UNAVAILABLE') {
        return make(FailureKind.storageUnavailable,
            'Kho lưu ảnh chưa sẵn sàng. Vui lòng thử lại sau.');
      }
      return make(FailureKind.server, 'Máy chủ đang gặp lỗi. Vui lòng thử lại.');
  }
  if (status >= 500) {
    // Không hiện message của lỗi 5xx: có thể lộ chi tiết nội bộ.
    return ApiFailure(FailureKind.server, 'Máy chủ đang gặp lỗi. Vui lòng thử lại.',
        status: status, code: code);
  }
  return make(FailureKind.unknown, 'Không thể hoàn tất thao tác. Vui lòng thử lại.');
}

/// Mã `Idempotency-Key` (UUID v4). Sinh một lần cho mỗi lần mở form và giữ
/// nguyên khi thử lại, để gửi lại sau lỗi mạng không tạo đơn trùng.
String newIdempotencyKey([Random? random]) {
  final r = random ?? Random.secure();
  final b = List<int>.generate(16, (_) => r.nextInt(256));
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;
  String hex(int from, int to) => b
      .sublist(from, to)
      .map((e) => e.toRadixString(16).padLeft(2, '0'))
      .join();
  return '${hex(0, 4)}-${hex(4, 6)}-${hex(6, 8)}-${hex(8, 10)}-${hex(10, 16)}';
}

/// Chạy một lời gọi API và đổi mọi [DioException] thành [ApiFailure]. Dữ liệu trả về sai
/// định dạng cũng thành [ApiFailure] (không làm sập màn hình).
Future<T> guardApi<T>(Future<T> Function() call) async {
  try {
    return await call();
  } on DioException catch (e) {
    throw toApiFailure(e);
  } on TypeError {
    throw const ApiFailure(
        FailureKind.unknown, 'Dữ liệu máy chủ trả về không đúng định dạng.');
  }
}
