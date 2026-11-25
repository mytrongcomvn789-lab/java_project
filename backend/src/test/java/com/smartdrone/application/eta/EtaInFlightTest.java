package com.smartdrone.application.eta;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** 7.2: ETA cho đơn đang bay (fixture, không phải số đo thật). */
class EtaInFlightTest {
    private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");

    private static EtaEstimate fly(Double speed, Instant departed) {
        return EtaCalculator.estimateInFlight(10.0, 106.0, 11.0, 106.0, speed, departed, NOW);
    }

    @Test
    void etaIsDeparturePlusTravelWithoutStationWait() {
        // 111.19 km / 60 = 112 phút; cất cánh 30 phút trước -> còn 82 phút
        EtaEstimate e = fly(60.0, NOW.minusSeconds(30 * 60));
        assertTrue(e.computed());
        assertEquals(0, e.waitMinutes());
        assertEquals(112, e.travelMinutes());
        assertEquals(NOW.minusSeconds(30 * 60).plusSeconds(112 * 60), e.etaAt());
        assertEquals(82, e.minutesFromNow());
        assertTrue(e.limitations().contains(EtaCalculator.LIMIT_DEPARTURE_FROM_HISTORY));
        assertFalse(e.limitations().contains(EtaCalculator.LIMIT_OVERDUE));
    }

    @Test
    void overdueFlightNeverReturnsPastEta() {
        EtaEstimate e = fly(60.0, NOW.minusSeconds(10 * 3600));
        assertEquals(NOW, e.etaAt());
        assertEquals(0, e.minutesFromNow());
        assertTrue(e.limitations().contains(EtaCalculator.LIMIT_OVERDUE));
    }

    @Test
    void unknownOrFutureDepartureFallsBackToNow() {
        assertEquals(NOW.plusSeconds(112 * 60), fly(60.0, null).etaAt());
        assertEquals(NOW.plusSeconds(112 * 60), fly(60.0, NOW.plusSeconds(3600)).etaAt());
    }

    @Test
    void invalidInputsAreRejected() {
        assertEquals(EtaEstimate.REASON_SPEED_INVALID, fly(0.0, NOW).reason());
        assertEquals(EtaEstimate.REASON_SPEED_INVALID, fly(null, NOW).reason());
        assertEquals(EtaEstimate.REASON_COORDINATES_MISSING,
                EtaCalculator.estimateInFlight(null, null, 11.0, 106.0, 40.0, NOW, NOW).reason());
        assertEquals(EtaEstimate.REASON_COORDINATES_MISSING,
                EtaCalculator.estimateInFlight(10.0, 106.0, 0.0, 0.0, 40.0, NOW, NOW).reason());
    }
}
