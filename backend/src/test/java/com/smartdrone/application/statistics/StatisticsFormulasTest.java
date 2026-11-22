package com.smartdrone.application.statistics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.smartdrone.application.statistics.dto.OrderCounts;
import com.smartdrone.domain.enums.OrderStatus;

/** 5.1: công thức thuần (không DB). Dữ liệu ở đây là fixture, chỉ để kiểm tra công thức. */
class StatisticsFormulasTest {

    private static Map<OrderStatus, Long> of(Object... pairs) {
        Map<OrderStatus, Long> m = new EnumMap<>(OrderStatus.class);
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((OrderStatus) pairs[i], ((Number) pairs[i + 1]).longValue());
        }
        return m;
    }

    @Test
    void everyStatusBelongsToExactlyOneGroupSoTotalNeverDoubleCounts() {
        Map<OrderStatus, Long> all = new EnumMap<>(OrderStatus.class);
        for (OrderStatus s : OrderStatus.values()) all.put(s, 1L);
        OrderCounts c = StatisticsFormulas.counts(all);
        assertEquals(OrderStatus.values().length, c.total());
        assertEquals(1, c.pending());
        assertEquals(5, c.inProgress()); // APPROVED, SCHEDULED, PREPARING, IN_TRANSIT, AWAITING_PICKUP
        assertEquals(1, c.delivered());
        assertEquals(1, c.failed());
        assertEquals(1, c.returning());
        assertEquals(1, c.returned());
        assertEquals(1, c.cancelled());
        assertEquals(1, c.rejected());
    }

    @Test
    void successRateUsesOnlyOrdersWithADeliveryOutcome() {
        // 3 giao, 1 thất bại, 1 đang hoàn, 1 đã hoàn => mẫu 6; pending/inProgress/cancelled/rejected không vào mẫu.
        var c = StatisticsFormulas.counts(of(
                OrderStatus.DELIVERED, 3, OrderStatus.FAILED, 1, OrderStatus.RETURNING, 1, OrderStatus.RETURNED, 1,
                OrderStatus.PENDING, 4, OrderStatus.SCHEDULED, 2, OrderStatus.CANCELLED, 5, OrderStatus.REJECTED, 7));
        var r = StatisticsFormulas.rates(c);
        assertEquals(6, r.deliverySample());
        assertEquals(0.5, r.successRate(), 1e-9);
        assertEquals(2.0 / 6, r.returnRate(), 1e-9);
        assertEquals(5.0 / c.total(), r.cancelRate(), 1e-9);
    }

    @Test
    void noDataOrZeroDenominatorGivesNullNotZero() {
        var empty = StatisticsFormulas.counts(Map.of());
        assertEquals(OrderCounts.EMPTY, empty);
        var r = StatisticsFormulas.rates(empty);
        assertNull(r.successRate());
        assertNull(r.returnRate());
        assertNull(r.cancelRate());
        assertEquals(0, r.deliverySample());

        // Chỉ có đơn đang xử lý/hủy: tỷ lệ giao không có mẫu => null, tỷ lệ hủy vẫn tính được.
        var onlyCancelled = StatisticsFormulas.rates(StatisticsFormulas.counts(of(OrderStatus.CANCELLED, 2)));
        assertNull(onlyCancelled.successRate());
        assertEquals(1.0, onlyCancelled.cancelRate(), 1e-9);

        assertNull(StatisticsFormulas.ratio(5, 0));
        assertEquals(0.0, StatisticsFormulas.ratio(0, 3), 1e-9); // 0/3 là 0 thật, khác với "không có mẫu"
    }

    @Test
    void attemptsAreCountedSeparatelyFromOrders() {
        // 1 đơn thử 3 lần = 3 attempt nhưng chỉ 1 đơn có retry.
        var a = StatisticsFormulas.attempts(7, 3, 2, 4, 1);
        assertEquals(7, a.totalAttempts());
        assertEquals(3, a.retryAttempts());
        assertEquals(1, a.ordersWithRetry());
        assertEquals(4, a.ordersWithAttempt());
        assertEquals(2, a.activeAttempts());
        assertEquals(5, a.releasedAttempts());
        assertEquals(0.25, a.retryRate(), 1e-9);
        assertNull(StatisticsFormulas.attempts(0, 0, 0, 0, 0).retryRate());
    }

    @Test
    void deliveryTimeStatsAndExcludedOrders() {
        var t = StatisticsFormulas.deliveryTime(List.of(1800L, 1200L), 3);
        assertEquals(2, t.count());
        assertEquals(1, t.excludedOrders());
        assertEquals(1500.0, t.avgSeconds(), 1e-9);
        assertEquals(1200L, t.minSeconds());
        assertEquals(1800L, t.maxSeconds());

        var none = StatisticsFormulas.deliveryTime(List.of(), 0);
        assertEquals(0, none.count());
        assertNull(none.avgSeconds());
        assertNull(none.minSeconds());
        assertNull(none.maxSeconds());
    }
}
