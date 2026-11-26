package com.smartdrone.application.eta;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 7.2: ETA = mốc xuất phát + (khoảng cách ÷ vận tốc) + thời gian chờ trạm. Hàm thuần, không DB, không AI.
 * Ước tính nghiệp vụ, không điều khiển drone. Không dùng GPS drone (repo chưa có).
 */
public final class EtaCalculator {
    public static final String LIMIT_STRAIGHT_LINE = "STRAIGHT_LINE_DISTANCE";
    public static final String LIMIT_CONSTANT_SPEED = "CONSTANT_SPEED_UNCALIBRATED";
    public static final String LIMIT_CONSTANT_WAIT = "CONSTANT_STATION_WAIT";
    public static final String LIMIT_START_CLAMPED = "START_CLAMPED_TO_NOW";
    public static final String LIMIT_OVERDUE = "OVERDUE_NO_LIVE_POSITION";
    public static final String LIMIT_DEPARTURE_FROM_HISTORY = "DEPARTURE_FROM_STATUS_HISTORY";

    private EtaCalculator() { }

    /**
     * @param start mốc xuất phát dự kiến (null = bắt đầu ngay); nếu ở quá khứ thì bị chặn về now.
     */
    public static EtaEstimate estimate(Double pickupLat, Double pickupLon, Double dropLat, Double dropLon,
                                       Double speedKmh, Integer waitMinutes, Instant start, Instant now) {
        if (speedKmh == null || !Double.isFinite(speedKmh) || speedKmh <= 0) {
            return EtaEstimate.failed(EtaEstimate.REASON_SPEED_INVALID, now);
        }
        if (waitMinutes == null || waitMinutes < 0) {
            return EtaEstimate.failed(EtaEstimate.REASON_WAIT_INVALID, now);
        }
        if (!GeoDistance.isValid(pickupLat, pickupLon) || !GeoDistance.isValid(dropLat, dropLon)) {
            return EtaEstimate.failed(EtaEstimate.REASON_COORDINATES_MISSING, now);
        }
        List<String> limits = new ArrayList<>(List.of(LIMIT_STRAIGHT_LINE, LIMIT_CONSTANT_SPEED, LIMIT_CONSTANT_WAIT));
        Instant from = start;
        if (from == null || from.isBefore(now)) {
            if (from != null) limits.add(LIMIT_START_CLAMPED);
            from = now;
        }
        double km = GeoDistance.haversineKm(pickupLat, pickupLon, dropLat, dropLon);
        int travel = (int) Math.ceil(km / speedKmh * 60.0);
        Instant etaAt = from.plus(Duration.ofMinutes((long) travel + waitMinutes));
        long minutesFromNow = (long) Math.ceil(Duration.between(now, etaAt).toSeconds() / 60.0);
        return new EtaEstimate(true, null, etaAt, minutesFromNow, travel, waitMinutes, km,
                EtaEstimate.METHOD, now, List.copyOf(limits));
    }

    /**
     * Đơn đang bay: ETA = thời điểm cất cánh + thời gian di chuyển (không cộng chờ trạm vì đã qua).
     * Không có GPS drone nên không biết vị trí thật; nếu đã quá hạn ước tính thì đặt ETA = now và ghi OVERDUE.
     */
    public static EtaEstimate estimateInFlight(Double pickupLat, Double pickupLon, Double dropLat, Double dropLon,
                                               Double speedKmh, Instant departedAt, Instant now) {
        if (speedKmh == null || !Double.isFinite(speedKmh) || speedKmh <= 0) {
            return EtaEstimate.failed(EtaEstimate.REASON_SPEED_INVALID, now);
        }
        if (!GeoDistance.isValid(pickupLat, pickupLon) || !GeoDistance.isValid(dropLat, dropLon)) {
            return EtaEstimate.failed(EtaEstimate.REASON_COORDINATES_MISSING, now);
        }
        List<String> limits = new ArrayList<>(List.of(LIMIT_STRAIGHT_LINE, LIMIT_CONSTANT_SPEED,
                LIMIT_DEPARTURE_FROM_HISTORY));
        Instant from = (departedAt == null || departedAt.isAfter(now)) ? now : departedAt;
        double km = GeoDistance.haversineKm(pickupLat, pickupLon, dropLat, dropLon);
        int travel = (int) Math.ceil(km / speedKmh * 60.0);
        Instant etaAt = from.plus(Duration.ofMinutes(travel));
        if (etaAt.isBefore(now)) {
            etaAt = now;
            limits.add(LIMIT_OVERDUE);
        }
        long minutesFromNow = (long) Math.ceil(Duration.between(now, etaAt).toSeconds() / 60.0);
        return new EtaEstimate(true, null, etaAt, minutesFromNow, travel, 0, km,
                EtaEstimate.METHOD, now, List.copyOf(limits));
    }
}
