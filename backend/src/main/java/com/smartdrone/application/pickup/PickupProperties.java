package com.smartdrone.application.pickup;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** app.pickup.*: thiếu hoặc sai thì ứng dụng dừng ngay lúc khởi động. */
@ConfigurationProperties(prefix = "app.pickup")
public record PickupProperties(String secret, Duration challengeTtl, Integer maxFailedAttempts, Integer otpDigits) {

    public PickupProperties {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("app.pickup.secret phải có ít nhất 32 ký tự (biến APP_PICKUP_SECRET)");
        }
        if (challengeTtl == null || challengeTtl.isZero() || challengeTtl.isNegative()) {
            throw new IllegalStateException("app.pickup.challenge-ttl phải > 0");
        }
        if (maxFailedAttempts == null || maxFailedAttempts < 1 || maxFailedAttempts > 10) {
            throw new IllegalStateException("app.pickup.max-failed-attempts phải trong khoảng 1..10");
        }
        if (otpDigits == null || otpDigits < 4 || otpDigits > 9) {
            throw new IllegalStateException("app.pickup.otp-digits phải trong khoảng 4..9");
        }
    }
}