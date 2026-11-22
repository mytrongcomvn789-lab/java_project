package com.smartdrone.application.statistics;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;

/** 5.1: biên ngày theo múi giờ Asia/Ho_Chi_Minh (UTC+7), giới hạn khoảng và mặc định. */
class StatisticsPeriodTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T18:00:00Z"), ZoneOffset.UTC);
    // 18:00Z = 01:00 ngày 21/11 giờ Việt Nam: "hôm nay" phải là 21, không phải 20.

    @Test
    void dayBoundariesFollowVietnamTimeNotUtc() {
        var p = StatisticsPeriod.of(LocalDate.parse("2026-11-10"), LocalDate.parse("2026-11-10"), null, null, CLOCK);
        assertEquals(Instant.parse("2026-11-09T17:00:00Z"), p.start()); // 00:00 VN ngày 10
        assertEquals(Instant.parse("2026-11-10T17:00:00Z"), p.end());   // 00:00 VN ngày 11, loại trừ
        assertEquals(1, p.days());
        assertEquals(DateBasis.CREATED, p.basis());
    }

    @Test
    void defaultsToLast30DaysEndingTodayInVietnam() {
        var p = StatisticsPeriod.of(null, null, null, null, CLOCK);
        assertEquals(LocalDate.parse("2026-11-21"), p.to());
        assertEquals(LocalDate.parse("2026-10-23"), p.from());
        assertEquals(30, p.days());
    }

    @Test
    void rangeLimitIs366DaysInclusive() {
        var from = LocalDate.parse("2026-01-01");
        assertDoesNotThrow(() -> StatisticsPeriod.of(from, from.plusDays(365), null, null, CLOCK)); // 366 ngày
        var ex = assertThrows(ApiException.class,
                () -> StatisticsPeriod.of(from, from.plusDays(366), null, null, CLOCK)); // 367 ngày
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }

    @Test
    void fromAfterToIsRejected() {
        var ex = assertThrows(ApiException.class, () -> StatisticsPeriod.of(
                LocalDate.parse("2026-11-11"), LocalDate.parse("2026-11-10"), null, null, CLOCK));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }
}
