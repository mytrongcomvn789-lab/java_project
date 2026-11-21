package com.smartdrone.application.pickup.dto;

import java.time.Instant;
import java.util.UUID;

/** Mã chỉ trả cho customer chủ đơn; luôn gửi kèm Cache-Control: no-store. toString() không lộ mã. */
public record PickupCodeView(UUID challengeId, UUID orderId, String otp, String qrPayload, Instant expiresAt) {
    @Override
    public String toString() {
        return "PickupCodeView[challengeId=" + challengeId + ", orderId=" + orderId + ", expiresAt=" + expiresAt + "]";
    }
}