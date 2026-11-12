package com.smartdrone.domain.workflow;

/** Dữ liệu bắt buộc phải có trong request thì cạnh mới hợp lệ. */
public enum RequiredField {
    REASON,              // lý do (từ chối, thất bại, hoàn hàng)
    ATTEMPT_ID,          // lần thử giao hàng
    CONFIRMATION_PHOTO   // ảnh xác nhận đã giao
}