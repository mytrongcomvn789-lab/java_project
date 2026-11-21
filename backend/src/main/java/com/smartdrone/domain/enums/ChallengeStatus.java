package com.smartdrone.domain.enums;

public enum ChallengeStatus {
    ACTIVE,   // còn dùng được
    USED,     // đã dùng một lần để xác nhận giao
    REVOKED,  // bị thay bằng challenge mới
    LOCKED    // nhập sai quá số lần cho phép
}