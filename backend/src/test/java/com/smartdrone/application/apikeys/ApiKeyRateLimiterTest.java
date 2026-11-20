package com.smartdrone.application.apikeys;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ApiKeyRateLimiterTest {

    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void allowsUpToLimitThenBlocksWithinSameWindow() {
        MutableClock clock = new MutableClock();
        ApiKeyRateLimiter limiter = new ApiKeyRateLimiter(clock);
        UUID key = UUID.randomUUID();
        for (int i = 0; i < 3; i++) assertTrue(limiter.allow(key, 3), "request #" + i + " phải được phép");
        assertFalse(limiter.allow(key, 3), "request thứ 4 trong cùng cửa sổ phải bị chặn");
    }

    @Test
    void windowSlidesAfter60Seconds() {
        MutableClock clock = new MutableClock();
        ApiKeyRateLimiter limiter = new ApiKeyRateLimiter(clock);
        UUID key = UUID.randomUUID();
        assertTrue(limiter.allow(key, 1));
        assertFalse(limiter.allow(key, 1));
        clock.advance(61);
        assertTrue(limiter.allow(key, 1), "qua 60s thì hit cũ phải rơi khỏi cửa sổ");
    }

    @Test
    void differentKeysHaveIndependentBudgets() {
        MutableClock clock = new MutableClock();
        ApiKeyRateLimiter limiter = new ApiKeyRateLimiter(clock);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(limiter.allow(a, 1));
        assertFalse(limiter.allow(a, 1));
        assertTrue(limiter.allow(b, 1), "key khác không bị ảnh hưởng bởi key a");
    }
}