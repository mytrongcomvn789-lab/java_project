package com.smartdrone.application.ai.analytics;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.smartdrone.application.statistics.*;
import com.smartdrone.application.statistics.dto.*;
import com.smartdrone.domain.enums.*;

class OperationsRulesTest {
    private final OperationsRules rules = new OperationsRules();
    static OperationsDataset data(long delivered, long failed, long returned, long... daily) {
        var counts = StatisticsFormulas.counts(Map.of(OrderStatus.DELIVERED, delivered,
                OrderStatus.FAILED, failed, OrderStatus.RETURNED, returned));
        LocalDate from = LocalDate.of(2026, 11, 1);
        var summary = new OrderStatsSummary(from, from.plusDays(daily.length - 1), "Asia/Ho_Chi_Minh",
                DateBasis.CREATED, null, counts, Map.of(), StatisticsFormulas.rates(counts),
                StatisticsFormulas.attempts(0, 0, 0, 0, 0),
                StatisticsFormulas.deliveryTime(List.of(), delivered));
        var days = new ArrayList<DailyOrderStats>();
        for (int i = 0; i < daily.length; i++)
            days.add(new DailyOrderStats(from.plusDays(i), new OrderCounts(daily[i],daily[i],0,0,0,0,0,0,0)));
        return new OperationsDataset(summary, days, List.of(), Instant.parse("2026-11-25T00:00:00Z"));
    }
    static boolean has(OperationsRules.Findings f, String id) {
        return f.evidence().stream().anyMatch(e -> e.id().equals(id));
    }
    @Test void failureIsNotOneMinusSuccessWhenReturnsExist() {
        var f = rules.evaluate(data(10, 2, 8, 10, 10));
        assertEquals(0.1, f.failureRate()); assertEquals(20, f.failureSample());
        assertFalse(has(f, "HIGH_FAILURE"));
    }
    @Test void alertRequiresBothThresholdAndMinimumSample() {
        assertTrue(has(rules.evaluate(data(16,4,0,10,10)), "HIGH_FAILURE"));
        assertFalse(has(rules.evaluate(data(15,4,0,9,10)), "HIGH_FAILURE"));
        assertFalse(has(rules.evaluate(data(17,3,0,10,10)), "HIGH_FAILURE"));
    }
    @Test void emptyDenominatorsAreNullNotZeroAndWarn() {
        var f = rules.evaluate(data(0,0,0,0,0));
        assertNull(f.failureRate()); assertNull(f.trend().changeRate());
        assertTrue(f.warnings().containsAll(List.of("NO_ORDERS_IN_SCOPE", "LOW_DELIVERY_SAMPLE",
                "NO_DELIVERY_DURATION_SAMPLE", "TREND_ZERO_BASELINE")));
    }
    @Test void oddTrendUsesEqualWindowsAndDisclosesOmittedDay() {
        var f = rules.evaluate(data(20,0,0,2,3,999,5,5));
        assertEquals(2, f.trend().daysPerWindow()); assertEquals(5, f.trend().firstOrders());
        assertEquals(10, f.trend().lastOrders()); assertEquals(1.0, f.trend().changeRate());
        assertEquals(LocalDate.of(2026,11,3), f.trend().omittedMiddleDay());
    }
    @Test void singleDayHasNoInventedTrend() {
        var f = rules.evaluate(data(1,0,0,1));
        assertNull(f.trend()); assertTrue(f.warnings().contains("TREND_REQUIRES_TWO_DAYS"));
    }
    @Test void decliningTrendCanBeNegative() {
        var f = rules.evaluate(data(15,0,0,10,5));
        assertEquals(-5, f.trend().delta()); assertEquals(-0.5, f.trend().changeRate());
    }
    @Test void loadThresholdUsesBookedCapacityAndWarnsForUndefinedCapacity() {
        var d = data(20,0,0,10,10);
        var rows = List.of(load(4,5), load(0,0), load(3,5));
        var f = rules.evaluate(new OperationsDataset(d.summary(),d.daily(),rows,d.collectedAt()));
        assertTrue(has(f,"HIGH_LOAD"));
        assertTrue(f.warnings().contains("UNDEFINED_STATION_UTILIZATION"));
        assertTrue(f.evidence().stream().filter(e -> e.id().equals("HIGH_LOAD")).findFirst().get().text().contains("Có 1 trạm"));
    }
    @Test void durationSampleExclusionsRemainVisible() {
        var f = rules.evaluate(data(20,0,0,10,10));
        assertTrue(has(f,"MISSING_TIMESTAMPS"));
        assertFalse(has(f,"DELIVERY_TIME"));
    }
    private static StationLoadStat load(long booked, int capacity) {
        return new StationLoadStat(UUID.randomUUID(),"fixture",StationStatus.ACTIVE,capacity,
                Instant.EPOCH,Instant.EPOCH.plusSeconds(900),booked,Math.max(0,capacity-booked),
                99,StatisticsFormulas.ratio(booked,capacity));
    }
}
