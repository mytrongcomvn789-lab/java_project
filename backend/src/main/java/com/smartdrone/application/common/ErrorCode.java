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
    LAST_ADMIN_PROTECTED(HttpStatus.CONFLICT, "Không thể khóa hoặc hạ quyền quản trị viên cuối cùng"),
    ROLE_LOCKED(HttpStatus.CONFLICT, "Quyền của vai trò quản trị viên được khóa, không thể thay đổi"),
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
    SLOT_FULL(HttpStatus.CONFLICT, "Khung giờ này đã đủ chỗ tại trạm, vui lòng chọn khung giờ khác"),
    STATION_UNAVAILABLE(HttpStatus.CONFLICT, "Trạm hiện không hoạt động nên không thể lên lịch"),
    RETRY_LIMIT_REACHED(HttpStatus.CONFLICT, "Đơn đã hết số lần giao lại cho phép, hãy chuyển sang hoàn hàng"),
    DATA_CONFLICT(HttpStatus.CONFLICT, "Dữ liệu vừa bị thay đổi hoặc trùng với dữ liệu hiện có, vui lòng tải lại và thử lại"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "Idempotency-Key đã được dùng cho nội dung yêu cầu khác"),
    API_KEY_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "API key không hợp lệ, đã hết hạn hoặc đã bị thu hồi"),
    API_KEY_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Vượt quá giới hạn tần suất của API key"),
    PICKUP_CODE_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "Mã nhận hàng không đúng hoặc không còn hiệu lực"),
    PICKUP_CODE_EXPIRED(HttpStatus.GONE, "Mã nhận hàng đã hết hạn, khách cần mở lại mã"),
    PICKUP_CODE_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "Mã nhận hàng đã bị khóa do nhập sai quá số lần cho phép"),
    PICKUP_CODE_USED(HttpStatus.CONFLICT, "Mã nhận hàng đã được sử dụng"),
    AI_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE, "Tính năng AI chưa được cấu hình"),
    AI_PROVIDER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Dịch vụ AI tạm thời không khả dụng, vui lòng thử lại sau"),
    AI_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Dịch vụ AI phản hồi quá chậm, vui lòng thử lại sau"),
    AI_RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Bạn gửi yêu cầu AI quá nhanh, vui lòng thử lại sau"),
    AI_BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Đã hết hạn mức sử dụng AI trong ngày"),
    AI_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY, "Dịch vụ AI trả về kết quả không hợp lệ"),
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
