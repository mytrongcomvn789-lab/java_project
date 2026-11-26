package com.smartdrone.application.eta;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** 7.2: công thức ETA thuần. Số liệu là fixture, không phải số đo thật. */
class EtaCalculatorTest {
    private static final Instant NOW = Instant.parse("2026-11-25T03:00:00Z");
    // 1 độ vĩ ≈ 111.19 km
    private static EtaEstimate est(Double lat2, Double speed, Integer wait, Instant start) {
        return EtaCalculator.estimate(10.0, 106.0, lat2, 106.0, speed, wait, start, NOW);
    }

    @Test
    void etaIsTravelPlusWait() {
        // 111.19 km / 60 km/h = 111.19 phút -> 112, cộng chờ 8 -> 120
        EtaEstimate e = est(11.0, 60.0, 8, null);
        assertTrue(e.computed());
        assertEquals(112, e.travelMinutes());
        assertEquals(8, e.waitMinutes());
        assertEquals(NOW.plusSeconds(120 * 60L), e.etaAt());
        assertEquals(120, e.minutesFromNow());
        assertEquals(111.19, e.distanceKm(), 0.05);
    }

    @Test
    void methodIsFormulaNotMl() {
        EtaEstimate e = est(10.1, 40.0, 5, null);
        assertEquals("FORMULA_V1", e.method());
        assertEquals(NOW, e.computedAt());
        assertTrue(e.limitations().contains(EtaCalculator.LIMIT_STRAIGHT_LINE));
        assertTrue(e.limitations().contains(EtaCalculator.LIMIT_CONSTANT_SPEED));
    }

    @Test
    void samePlaceStillCostsStationWait() {
        EtaEstimate e = est(10.0, 40.0, 5, null);
        assertTrue(e.computed());
        assertEquals(0, e.travelMinutes());
        assertEquals(5, e.minutesFromNow());
    }

    @Test
    void zeroWaitIsValid() {
        EtaEstimate e = est(10.1, 40.0, 0, null);
        assertTrue(e.computed());
        assertEquals((long) e.travelMinutes(), e.minutesFromNow());
    }

    @Test
    void futureStartShiftsEta() {
        Instant start = NOW.plusSeconds(3600);
        EtaEstimate e = est(10.0, 40.0, 5, start);
        assertEquals(start.plusSeconds(300), e.etaAt());
        assertEquals(65, e.minutesFromNow());
        assertFalse(e.limitations().contains(EtaCalculator.LIMIT_START_CLAMPED));
    }

    @Test
    void pastStartIsClampedToNowAndNeverYieldsPastEta() {
        EtaEstimate e = est(10.0, 40.0, 5, NOW.minusSeconds(7200));
        assertEquals(NOW.plusSeconds(300), e.etaAt());
        assertTrue(e.minutesFromNow() >= 0);
        assertTrue(e.limitations().contains(EtaCalculator.LIMIT_START_CLAMPED));
    }

    @Test
    void missingOrInvalidCoordinatesAreNotGuessed() {
        for (EtaEstimate e : new EtaEstimate[] {
                EtaCalculator.estimate(null, 106.0, 11.0, 106.0, 40.0, 5, null, NOW),
                EtaCalculator.estimate(10.0, 106.0, null, null, 40.0, 5, null, NOW),
                EtaCalculator.estimate(0.0, 0.0, 11.0, 106.0, 40.0, 5, null, NOW),
                EtaCalculator.estimate(10.0, 106.0, Double.NaN, 106.0, 40.0, 5, null, NOW),
                EtaCalculator.estimate(10.0, 106.0, 95.0, 106.0, 40.0, 5, null, NOW)}) {
            assertFalse(e.computed());
            assertEquals(EtaEstimate.REASON_COORDINATES_MISSING, e.reason());
            assertNull(e.etaAt());
            assertNull(e.minutesFromNow());
        }
    }

    @Test
    void invalidSpeedIsRejected() {
        for (Double speed : new Double[] {null, 0.0, -10.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            EtaEstimate e = est(11.0, speed, 5, null);
            assertFalse(e.computed(), "speed=" + speed);
            assertEquals(EtaEstimate.REASON_SPEED_INVALID, e.reason());
        }
    }

    @Test
    void invalidWaitIsRejected() {
        assertEquals(EtaEstimate.REASON_WAIT_INVALID, est(11.0, 40.0, -1, null).reason());
        assertEquals(EtaEstimate.REASON_WAIT_INVALID, est(11.0, 40.0, null, null).reason());
    }

    @Test
    void propertiesRejectBadConfig() {
        assertThrows(IllegalStateException.class, () -> new EtaProperties(null, 5));
        assertThrows(IllegalStateException.class, () -> new EtaProperties(0.0, 5));
        assertThrows(IllegalStateException.class, () -> new EtaProperties(-1.0, 5));
        assertThrows(IllegalStateException.class, () -> new EtaProperties(40.0, null));
        assertThrows(IllegalStateException.class, () -> new EtaProperties(40.0, -1));
        assertDoesNotThrow(() -> new EtaProperties(40.0, 0));
    }
}
