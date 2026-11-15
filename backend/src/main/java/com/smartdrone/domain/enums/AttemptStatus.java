package com.smartdrone.domain.enums;

/** Trạng thái một lần lên lịch. Chỉ ACTIVE mới chiếm chỗ ở trạm. */
public enum AttemptStatus {
    ACTIVE,   // đang giữ chỗ trong slot
    RELEASED  // đã trả chỗ (ví dụ đơn thất bại, chờ giao lại)
}