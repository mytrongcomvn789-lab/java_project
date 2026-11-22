package com.smartdrone.infrastructure.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.smartdrone.application.statistics.DateBasis;
import com.smartdrone.domain.enums.AttemptStatus;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

/**
 * 5.1: truy vấn thống kê (chỉ đọc). Viết bằng JPQL, không dùng hàm ngày của DB nên chạy được trên cả H2 lẫn
 * SQL Server. Phạm vi trạm luôn là điều kiện đầu tiên; "ids" không được rỗng (service xử lý trường hợp rỗng).
 * Cùng một đoạn lọc theo mốc ngày được dùng cho mọi truy vấn để các con số không lệch nhau.
 */
@Repository
public class StatisticsQueries {

    /** Trạng thái cuối: mỗi đơn có đúng một dòng lịch sử đi vào trạng thái này. */
    private static final Set<OrderStatus> TERMINAL =
            EnumSet.of(OrderStatus.DELIVERED, OrderStatus.RETURNED, OrderStatus.CANCELLED, OrderStatus.REJECTED);

    private static final String SCOPE = "(o.pickupStationId in :ids or o.dropoffStationId in :ids)";
    private static final String CREATED_IN_RANGE = "o.createdAt >= :s and o.createdAt < :e";
    private static final String COMPLETED_IN_RANGE =
            "exists (select 1 from OrderStatusHistory h where h.orderId = o.id and h.toStatus = o.status"
                    + " and h.toStatus in :terminal and h.occurredAt >= :s and h.occurredAt < :e)";

    @PersistenceContext
    private EntityManager em;

    /** Số đơn theo trạng thái hiện tại (GROUP BY trong DB). */
    public Map<OrderStatus, Long> countByStatus(DateBasis basis, Set<UUID> ids, Instant s, Instant e) {
        Query q = em.createQuery("select o.status, count(o) from Order o where " + SCOPE
                + " and " + orderInRange(basis) + " group by o.status");
        bind(q, basis, ids, s, e);
        Map<OrderStatus, Long> result = new EnumMap<>(OrderStatus.class);
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            result.put((OrderStatus) r[0], (Long) r[1]);
        }
        return result;
    }

    /** Cặp (mốc lọc, trạng thái) của từng đơn, chỉ hai cột nhẹ: dùng để chia theo ngày địa phương ở service. */
    public List<Object[]> dailyPoints(DateBasis basis, Set<UUID> ids, Instant s, Instant e) {
        String jpql = basis == DateBasis.CREATED
                ? "select o.createdAt, o.status from Order o where " + SCOPE + " and " + CREATED_IN_RANGE
                : "select h.occurredAt, o.status from Order o, OrderStatusHistory h"
                        + " where h.orderId = o.id and h.toStatus = o.status and h.toStatus in :terminal"
                        + " and h.occurredAt >= :s and h.occurredAt < :e and " + SCOPE;
        Query q = em.createQuery(jpql);
        bind(q, basis, ids, s, e);
        List<Object[]> rows = new ArrayList<>();
        for (Object row : q.getResultList()) {
            rows.add((Object[]) row);
        }
        return rows;
    }

    /**
     * Số lần giao: [tổng, retry, ACTIVE, số đơn khác nhau có attempt, số đơn khác nhau có retry].
     * CREATED lọc theo created_at của attempt; COMPLETED lấy attempt của các đơn hoàn tất trong khoảng.
     */
    public long[] attemptStats(DateBasis basis, Set<UUID> ids, Instant s, Instant e) {
        String where = basis == DateBasis.CREATED
                ? "a.createdAt >= :s and a.createdAt < :e and a.orderId in (select o.id from Order o where " + SCOPE + ")"
                : "a.orderId in (select o.id from Order o where " + SCOPE + " and " + COMPLETED_IN_RANGE + ")";
        Query q = em.createQuery("select count(a),"
                + " sum(case when a.attemptNo > 1 then 1 else 0 end),"
                + " sum(case when a.status = :active then 1 else 0 end),"
                + " count(distinct a.orderId),"
                + " count(distinct case when a.attemptNo > 1 then a.orderId else null end)"
                + " from DeliveryAttempt a where " + where);
        bind(q, basis, ids, s, e);
        q.setParameter("active", AttemptStatus.ACTIVE);
        Object[] r = (Object[]) q.getSingleResult();
        return new long[] {num(r[0]), num(r[1]), num(r[2]), num(r[3]), num(r[4])};
    }

    /**
     * Thời gian giao (giây) của các đơn DELIVERED đủ mốc: DISPATCH cuối cùng không sau CONFIRM.
     * Đơn thiếu DISPATCH không có dòng nào ở đây (service đếm vào excludedOrders).
     */
    public List<Long> deliverySeconds(DateBasis basis, Set<UUID> ids, Instant s, Instant e) {
        String range = basis == DateBasis.CREATED ? CREATED_IN_RANGE : "c.occurredAt >= :s and c.occurredAt < :e";
        Query q = em.createQuery("select c.occurredAt, max(d.occurredAt)"
                + " from OrderStatusHistory c, OrderStatusHistory d, Order o"
                + " where c.orderId = o.id and d.orderId = o.id and o.status = :delivered"
                + " and c.toStatus = :delivered and c.action = :confirm and d.action = :dispatch and d.occurredAt <= c.occurredAt"
                + " and " + SCOPE + " and " + range + " group by c.orderId, c.occurredAt");
        q.setParameter("ids", ids);
        q.setParameter("s", s);
        q.setParameter("e", e);
        q.setParameter("delivered", OrderStatus.DELIVERED);
        q.setParameter("confirm", OrderAction.CONFIRM);
        q.setParameter("dispatch", OrderAction.DISPATCH);
        List<Long> seconds = new ArrayList<>();
        for (Object row : q.getResultList()) {
            Object[] r = (Object[]) row;
            seconds.add(((Instant) r[0]).getEpochSecond() - ((Instant) r[1]).getEpochSecond());
        }
        return seconds;
    }

    private static String orderInRange(DateBasis basis) {
        return basis == DateBasis.CREATED ? CREATED_IN_RANGE : COMPLETED_IN_RANGE;
    }

    private static void bind(Query q, DateBasis basis, Set<UUID> ids, Instant s, Instant e) {
        q.setParameter("ids", ids);
        q.setParameter("s", s);
        q.setParameter("e", e);
        if (basis == DateBasis.COMPLETED) {
            q.setParameter("terminal", TERMINAL);
        }
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
