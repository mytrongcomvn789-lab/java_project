package com.smartdrone.application.common;

import org.springframework.http.HttpStatus;

// Mã lỗi nghiệp vụ trả về cho client (trường "code" trong JSON lỗi).
// Khớp mục 7 của APDDMP-9; các mã mới của task 2.5 được ghi trong docs/05-api/auth-api.md.
public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Dữ liệu không hợp lệ"),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "Cần đăng nhập để thực hiện thao tác này"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập đã hết hạn"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Refresh token không hợp lệ hoặc đã hết hạn"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này"),
    ACCOUNT_LOCKED(HttpStatus.FORBIDDEN, "Tài khoản đã bị khóa"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Không tìm thấy tài nguyên"),
    REQUEST_ERROR(HttpStatus.BAD_REQUEST, "Yêu cầu không hợp lệ"),
    SELF_ACCESS_CHANGE(HttpStatus.CONFLICT, "Không được tự thay đổi vai trò hoặc khóa tài khoản của mình"),
    EMAIL_ALREADY_EXISTS(HttpStatus.CONFLICT, "Email đã được đăng ký"),
    INVALID_CURRENT_PASSWORD(HttpStatus.BAD_REQUEST, "Mật khẩu hiện tại không đúng"),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Tệp vượt quá dung lượng cho phép"),
    FILE_TYPE_NOT_ALLOWED(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Loại tệp không được phép"),
    FILE_NOT_UPLOADED(HttpStatus.CONFLICT, "Chưa tìm thấy tệp trên kho lưu trữ"),
    FILE_INVALID_STATE(HttpStatus.CONFLICT, "Tệp không ở trạng thái phù hợp"),
    UPLOAD_LIMIT_REACHED(HttpStatus.TOO_MANY_REQUESTS, "Có quá nhiều yêu cầu tải lên đang chờ"),
    STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Kho lưu trữ tệp chưa sẵn sàng"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Không thể chuyển đơn hàng sang trạng thái này"),
    VERSION_CONFLICT(HttpStatus.PRECONDITION_FAILED, "Đơn hàng vừa được cập nhật bởi người khác, vui lòng tải lại"),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "Thiếu header If-Match"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống, vui lòng thử lại sau");
    
    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
