package com.smartdrone.application.charging;

import java.time.Duration;
import java.time.Instant;

import com.smartdrone.domain.entity.ChargingDevice;
import com.smartdrone.domain.enums.ChargerState;

/**
 * Công thức giả lập quá trình sạc (chưa có phần cứng thật): pin tăng đều theo thời gian đã sạc.
 * Mọi con số hiển thị (% pin, thời gian còn lại, giờ xong, nhiệt độ) đều tính từ đây nên luôn khớp nhau.
 */
public final class ChargingRules {
    /** Tốc độ sạc giả lập: 1% pin mỗi phút (0 → 100% mất khoảng 100 phút). */
    public static final double PERCENT_PER_MINUTE = 1.0;
    /** Thiết bị coi là trực tuyến nếu có heartbeat trong khoảng này. */
    public static final Duration HEARTBEAT_WINDOW = Duration.ofMinutes(2);

    private ChargingRules() { }

    /** Pin chính xác (có phần thập phân) của drone đang cắm; null nếu thiết bị không có drone. */
    private static Double exactPercent(ChargingDevice d, Instant now) {
        if (d.getDroneId() == null || d.getChargeStartedAt() == null || d.getStartPercent() == null) return null;
        double minutes = Math.max(0, Duration.between(d.getChargeStartedAt(), now).toMillis() / 60000.0);
        return Math.min(d.getTargetPercent(), d.getStartPercent() + PERCENT_PER_MINUTE * minutes);
    }

    public static Integer currentPercent(ChargingDevice d, Instant now) {
        Double p = exactPercent(d, now);
        return p == null ? null : (int) Math.floor(p + 1e-9);
    }

    public static ChargerState state(ChargingDevice d, Instant now) {
        if (d.isMaintenance()) return ChargerState.MAINTENANCE;
        Double p = exactPercent(d, now);
        if (p == null) return ChargerState.EMPTY;
        return p >= d.getTargetPercent() ? ChargerState.FULL : ChargerState.CHARGING;
    }

    /** Số phút còn lại để đạt mục tiêu (làm tròn lên); 0 nếu đã đầy; null nếu không có drone. */
    public static Long remainingMinutes(ChargingDevice d, Instant now) {
        Double p = exactPercent(d, now);
        if (p == null) return null;
        return (long) Math.max(0, Math.ceil((d.getTargetPercent() - p) / PERCENT_PER_MINUTE - 1e-9));
    }

    public static Long chargedMinutes(ChargingDevice d, Instant now) {
        if (d.getDroneId() == null || d.getChargeStartedAt() == null) return null;
        return Math.max(0, Duration.between(d.getChargeStartedAt(), now).toMinutes());
    }

    /** Thời điểm dự kiến đạt mục tiêu; null nếu không có drone. */
    public static Instant readyAt(ChargingDevice d) {
        if (d.getDroneId() == null || d.getChargeStartedAt() == null || d.getStartPercent() == null) return null;
        long millis = (long) Math.ceil((d.getTargetPercent() - d.getStartPercent()) / PERCENT_PER_MINUTE * 60000.0);
        return d.getChargeStartedAt().plusMillis(Math.max(0, millis));
    }

    /** Nhiệt độ pin giả lập: tăng nhẹ theo mức pin khi đang cắm; null nếu không có drone. */
    public static Double temperatureC(ChargingDevice d, Instant now) {
        Integer p = currentPercent(d, now);
        if (p == null) return null;
        return Math.round((25.0 + 0.15 * p) * 10.0) / 10.0;
    }

    public static boolean online(ChargingDevice d, Instant now) {
        Instant hb = d.getLastHeartbeatAt();
        return hb != null && !hb.isBefore(now.minus(HEARTBEAT_WINDOW));
    }
}
