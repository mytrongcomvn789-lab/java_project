package com.smartdrone.application.eta;

import org.springframework.boot.context.properties.ConfigurationProperties;

// 7.2: tham số ETA bản công thức (app.eta.*). Không có giá trị mặc định trong code:
// thiếu hoặc sai thì ứng dụng dừng ngay lúc khởi động.
// Giá trị trong application.yml là GIẢ ĐỊNH DEMO, chưa hiệu chuẩn bằng dữ liệu thật.
@ConfigurationProperties(prefix = "app.eta")
public record EtaProperties(Double speedKmh, Integer stationWaitMinutes) {
    public static final double MAX_SPEED_KMH = 200;
    public static final int MAX_WAIT_MINUTES = 240;

    public EtaProperties {
        if (speedKmh == null || !Double.isFinite(speedKmh) || speedKmh <= 0 || speedKmh > MAX_SPEED_KMH) {
            throw new IllegalStateException("app.eta.speed-kmh chưa cấu hình hoặc không hợp lệ (phải trong (0, 200])");
        }
        if (stationWaitMinutes == null || stationWaitMinutes < 0 || stationWaitMinutes > MAX_WAIT_MINUTES) {
            throw new IllegalStateException("app.eta.station-wait-minutes chưa cấu hình hoặc không hợp lệ (0..240)");
        }
    }
}
