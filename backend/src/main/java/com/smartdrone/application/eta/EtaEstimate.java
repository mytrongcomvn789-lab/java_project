package com.smartdrone.application.eta;

import java.time.Instant;
import java.util.List;

/**
 * Kết quả ETA bản công thức. etaAt/minutes null khi không tính được (xem reason).
 * method luôn là công thức, KHÔNG phải mô hình học máy.
 */
public record EtaEstimate(boolean computed, String reason, Instant etaAt, Long minutesFromNow,
                          Integer travelMinutes, Integer waitMinutes, Double distanceKm,
                          String method, Instant computedAt, List<String> limitations) {
    public static final String METHOD = "FORMULA_V1";
    public static final String REASON_COORDINATES_MISSING = "COORDINATES_MISSING";
    public static final String REASON_SPEED_INVALID = "SPEED_INVALID";
    public static final String REASON_WAIT_INVALID = "WAIT_INVALID";

    static EtaEstimate failed(String reason, Instant now) {
        return new EtaEstimate(false, reason, null, null, null, null, null, METHOD, now, List.of());
    }
}
