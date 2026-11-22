package com.smartdrone.application.statistics;

import java.util.List;
import java.util.Map;

import com.smartdrone.application.statistics.dto.AttemptStats;
import com.smartdrone.application.statistics.dto.DeliveryTimeStats;
import com.smartdrone.application.statistics.dto.OrderCounts;
import com.smartdrone.application.statistics.dto.OrderRates;
import com.smartdrone.domain.enums.OrderStatus;

/** 5.1: toàn bộ công thức ở một chỗ, không đụng DB nên test được trực tiếp. Xem mục 3–6 của tài liệu API. */
public final class StatisticsFormulas {
    private StatisticsFormulas() { }

    /** Gom số đơn theo trạng thái thành các nhóm; mỗi trạng thái thuộc đúng một nhóm nên không đếm đôi. */
    public static OrderCounts counts(Map<OrderStatus, Long> byStatus) {
        long pending = 0, inProgress = 0, delivered = 0, failed = 0, returning = 0,
                returned = 0, cancelled = 0, rejected = 0;
        for (var e : byStatus.entrySet()) {
            long n = e.getValue();
            switch (e.getKey()) {
                case PENDING -> pending += n;
                case APPROVED, SCHEDULED, PREPARING, IN_TRANSIT, AWAITING_PICKUP -> inProgress += n;
                case DELIVERED -> delivered += n;
                case FAILED -> failed += n;
                case RETURNING -> returning += n;
                case RETURNED -> returned += n;
                case CANCELLED -> cancelled += n;
                case REJECTED -> rejected += n;
            }
        }
        long total = pending + inProgress + delivered + failed + returning + returned + cancelled + rejected;
        return new OrderCounts(total, pending, inProgress, delivered, failed, returning, returned,
                cancelled, rejected);
    }

    /** Chia an toàn: mẫu <= 0 trả null (không phải 0). */
    public static Double ratio(long numerator, long denominator) {
        return denominator <= 0 ? null : (double) numerator / denominator;
    }

    public static OrderRates rates(OrderCounts c) {
        long sample = c.deliveryOutcomes();
        return new OrderRates(ratio(c.delivered(), sample), ratio(c.returning() + c.returned(), sample),
                ratio(c.cancelled(), c.total()), sample);
    }

    public static AttemptStats attempts(long total, long retry, long active, long orders, long retryOrders) {
        return new AttemptStats(total, retry, retryOrders, orders, active, total - active,
                ratio(retryOrders, orders));
    }

    /** durations: giây của từng đơn đủ mốc; deliveredOrders: số đơn DELIVERED trong phạm vi (để biết đơn thiếu mốc). */
    public static DeliveryTimeStats deliveryTime(List<Long> durations, long deliveredOrders) {
        long excluded = Math.max(0, deliveredOrders - durations.size());
        if (durations.isEmpty()) {
            return new DeliveryTimeStats(0, excluded, null, null, null);
        }
        long sum = 0, min = Long.MAX_VALUE, max = Long.MIN_VALUE;
        for (long d : durations) {
            sum += d;
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        return new DeliveryTimeStats(durations.size(), excluded, (double) sum / durations.size(), min, max);
    }
}
