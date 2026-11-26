package com.smartdrone.application.eta;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 7.2: haversine thuần, fixture toạ độ để kiểm tra công thức. */
class GeoDistanceTest {
    @Test
    void samePointIsZero() {
        assertEquals(0.0, GeoDistance.haversineKm(10.77, 106.70, 10.77, 106.70), 1e-9);
    }

    @Test
    void oneDegreeOfLatitudeIsAbout111Km() {
        assertEquals(111.19, GeoDistance.haversineKm(10, 106, 11, 106), 0.05);
    }

    @Test
    void oneDegreeOfLongitudeAtEquatorIsAbout111Km() {
        assertEquals(111.19, GeoDistance.haversineKm(0.5, 100, 0.5, 101), 0.2);
    }

    @Test
    void isSymmetric() {
        assertEquals(GeoDistance.haversineKm(10.77, 106.70, 21.03, 105.85),
                GeoDistance.haversineKm(21.03, 105.85, 10.77, 106.70), 1e-9);
    }

    @Test
    void hanoiToHoChiMinhIsAbout1140Km() {
        assertEquals(1140, GeoDistance.haversineKm(21.0285, 105.8542, 10.8231, 106.6297), 15);
    }

    @Test
    void validityRules() {
        assertTrue(GeoDistance.isValid(10.0, 106.0));
        assertFalse(GeoDistance.isValid(null, 106.0));
        assertFalse(GeoDistance.isValid(10.0, null));
        assertFalse(GeoDistance.isValid(0.0, 0.0));
        assertFalse(GeoDistance.isValid(91.0, 10.0));
        assertFalse(GeoDistance.isValid(10.0, 181.0));
        assertFalse(GeoDistance.isValid(Double.NaN, 10.0));
        assertFalse(GeoDistance.isValid(10.0, Double.POSITIVE_INFINITY));
    }
}
