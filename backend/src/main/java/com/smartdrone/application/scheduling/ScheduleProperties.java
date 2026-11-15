package com.smartdrone.application.scheduling;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Cấu hình lên lịch giao hàng (app.schedule.*). Không có giá trị mặc định trong code:
// thiếu hoặc sai thì ứng dụng dừng ngay lúc khởi động với thông báo rõ ràng.
// Hoạt động 24/24 nên không có giờ mở/đóng cửa; một ngày có 1440 / slotMinutes slot.
@ConfigurationProperties(prefix = "app.schedule")
public record ScheduleProperties(
        Integer slotMinutes,
        Integer minLeadMinutes,
        Integer maxDaysAhead) {

    private static final int MINUTES_PER_DAY = 24 * 60;

    public ScheduleProperties {
        if (slotMinutes == null || slotMinutes <= 0 || MINUTES_PER_DAY % slotMinutes != 0) {
            throw new IllegalStateException(
                    "app.schedule.slot-minutes chưa cấu hình hoặc không chia hết 1440 phút (ví dụ 15, 30, 60)");
        }
        if (minLeadMinutes == null || minLeadMinutes < 0) {
            throw new IllegalStateException("app.schedule.min-lead-minutes chưa cấu hình (phải >= 0)");
        }
        if (maxDaysAhead == null || maxDaysAhead < 1) {
            throw new IllegalStateException("app.schedule.max-days-ahead chưa cấu hình (phải >= 1)");
        }
    }

    public Duration slotLength() {
        return Duration.ofMinutes(slotMinutes);
    }

    public Duration minLead() {
        return Duration.ofMinutes(minLeadMinutes);
    }

    public Duration maxAhead() {
        return Duration.ofDays(maxDaysAhead);
    }
}