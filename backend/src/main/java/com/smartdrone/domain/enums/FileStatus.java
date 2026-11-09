package com.smartdrone.domain.enums;

public enum FileStatus {
    PENDING_UPLOAD, // đã cấp ticket, chưa xác nhận
    READY,          // đã kiểm tra hợp lệ, chưa gắn vào đơn
    ATTACHED,       // đã gắn vào tài nguyên nghiệp vụ
    REJECTED,       // sai loại/quá cỡ, object đã bị xóa
    EXPIRED,        // ticket hết hạn mà không upload
    DELETED         // đã dọn (READY quá hạn không gắn)
}