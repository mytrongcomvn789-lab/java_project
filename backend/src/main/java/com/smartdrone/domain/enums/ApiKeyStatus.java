package com.smartdrone.domain.enums;

/** Trạng thái do admin đặt. Hết hạn (expiresAt) được tính tại lúc xác thực, không có job riêng để tự chuyển trạng thái. */
public enum ApiKeyStatus {
    ACTIVE,
    REVOKED
}