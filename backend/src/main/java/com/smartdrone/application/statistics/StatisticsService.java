package com.smartdrone.application.statistics;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.orders.scope.OrderScope;
import com.smartdrone.application.orders.scope.OrderScopeResolver;
import com.smartdrone.application.scheduling.ScheduleProperties;
import com.smartdrone.application.scheduling.ScheduleSlotPolicy;
import com.smartdrone.application.statistics.dto.AttemptStats;
import com.smartdrone.application.statistics.dto.DailyOrderStats;
import com.smartdrone.application.statistics.dto.OrderCounts;
import com.smartdrone.application.statistics.dto.OrderStatsSummary;
import com.smartdrone.application.statistics.dto.StationLoadStat;
import com.smartdrone.application.stations.StationLoadRules;
import com.smartdrone.application.stations.dto.StationPackageCount;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;
import com.smartdrone.infrastructure.persistence.StationLoadRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;
import com.smartdrone.infrastructure.persistence.StatisticsQueries;

/**
 * 5.1: service thống kê DUY NHẤT cho dashboard (5.2) và báo cáo (5.3). Chỉ LOGISTICS_MANAGER; phạm vi dữ liệu
 * dùng lại OrderScopeResolver (cùng phạm vi xem đơn của 3.7). Số liệu do DB tổng hợp, frontend không tự tính lại.
 */
@Service
@PreAuthorize("hasRole('LOGISTICS_MANAGER')")
public class StatisticsService {
    private final StatisticsQueries queries;
    private final OrderScopeResolver scopes;
    private final StationRepository stations;
    private final StationLoadRepository load;
    private final DeliveryAttemptRepository attempts;
    private final ScheduleProperties schedule;
    private final ScheduleSlotPolicy slotPolicy;
    private final Clock clock;

    public StatisticsService(StatisticsQueries queries, OrderScopeResolver scopes, StationRepository stations,
            StationLoadRepository load, DeliveryAttemptRepository attempts, ScheduleProperties schedule,
            ScheduleSlotPolicy slotPolicy, Clock clock) {
        this.queries = queries; this.scopes = scopes; this.stations = stations; this.load = load;
        this.attempts = attempts; this.schedule = schedule; this.slotPolicy = slotPolicy; this.clock = clock;
    }

    /** Chuẩn hóa bộ lọc (múi giờ, biên ngày, giới hạn khoảng) — controller gọi hàm này rồi truyền vào các hàm dưới. */
    public StatisticsPeriod period(LocalDate from, LocalDate to, DateBasis basis, UUID stationId) {
        return StatisticsPeriod.of(from, to, basis, stationId, clock);
    }

    @Transactional(readOnly = true)
    public OrderStatsSummary summary(UUID actorId, StatisticsPeriod p) {
        Set<UUID> ids = allowedStations(actorId, p.stationId());
        OrderCounts counts = OrderCounts.EMPTY;
        Map<OrderStatus, Long> byStatus = new EnumMap<>(OrderStatus.class);
        AttemptStats attemptStats = StatisticsFormulas.attempts(0, 0, 0, 0, 0);
        List<Long> durations = List.of();
        if (!ids.isEmpty()) {
            byStatus.putAll(queries.countByStatus(p.basis(), ids, p.start(), p.end()));
            counts = StatisticsFormulas.counts(byStatus);
            long[] a = queries.attemptStats(p.basis(), ids, p.start(), p.end());
            attemptStats = StatisticsFormulas.attempts(a[0], a[1], a[2], a[3], a[4]);
            durations = queries.deliverySeconds(p.basis(), ids, p.start(), p.end());
        }
        // Luôn trả đủ 12 trạng thái (0 nếu không có) để client không phải đoán khóa thiếu.
        Map<OrderStatus, Long> full = new EnumMap<>(OrderStatus.class);
        for (OrderStatus s : OrderStatus.values()) {
            full.put(s, byStatus.getOrDefault(s, 0L));
        }
        return new OrderStatsSummary(p.from(), p.to(), StatisticsPeriod.ZONE.getId(), p.basis(), p.stationId(),
                counts, full, StatisticsFormulas.rates(counts), attemptStats,
                StatisticsFormulas.deliveryTime(durations, counts.delivered()));
    }

    @Transactional(readOnly = true)
    public List<DailyOrderStats> daily(UUID actorId, StatisticsPeriod p) {
        Set<UUID> ids = allowedStations(actorId, p.stationId());
        // Khởi tạo đủ mọi ngày trong khoảng để ngày không có đơn vẫn có dòng 0.
        Map<LocalDate, Map<OrderStatus, Long>> perDay = new TreeMap<>();
        for (int i = 0; i < p.days(); i++) {
            perDay.put(p.from().plusDays(i), new EnumMap<>(OrderStatus.class));
        }
        if (!ids.isEmpty()) {
            for (Object[] row : queries.dailyPoints(p.basis(), ids, p.start(), p.end())) {
                LocalDate day = ((Instant) row[0]).atZone(StatisticsPeriod.ZONE).toLocalDate();
                Map<OrderStatus, Long> bucket = perDay.get(day);
                if (bucket != null) {
                    bucket.merge((OrderStatus) row[1], 1L, Long::sum);
                }
            }
        }
        List<DailyOrderStats> result = new ArrayList<>(perDay.size());
        perDay.forEach((day, byStatus) -> result.add(new DailyOrderStats(day, StatisticsFormulas.counts(byStatus))));
        return result;
    }

    /** Tải trạm trong khung giờ chứa "at" (mặc định: khung giờ hiện tại, cùng lưới slot với 4.2). */
    @Transactional(readOnly = true)
    public List<StationLoadStat> stationLoad(UUID actorId, UUID stationId, Instant at) {
        Set<UUID> ids = allowedStations(actorId, stationId);
        if (ids.isEmpty()) {
            return List.of();
        }
        long step = schedule.slotLength().toSeconds();
        long ref = (at != null ? at : Instant.now(clock)).getEpochSecond();
        Instant slotStart = Instant.ofEpochSecond(Math.floorDiv(ref, step) * step);
        Instant slotEnd = slotPolicy.slotEnd(slotStart);

        Map<UUID, Long> preparing = toMap(load.countByPickupStation(StationLoadRules.PREPARING));
        Map<UUID, Long> waiting = toMap(load.countByDropoffStation(StationLoadRules.WAITING_PICKUP));

        return stations.findAll(Sort.by("name", "id")).stream()
                .filter(s -> ids.contains(s.getId()))
                .map(s -> stat(s, slotStart, slotEnd, preparing, waiting))
                .toList();
    }

    private StationLoadStat stat(Station s, Instant slotStart, Instant slotEnd,
            Map<UUID, Long> preparing, Map<UUID, Long> waiting) {
        long booked = attempts.countActiveOverlapping(s.getId(), slotStart, slotEnd);
        long processing = preparing.getOrDefault(s.getId(), 0L) + waiting.getOrDefault(s.getId(), 0L);
        return new StationLoadStat(s.getId(), s.getName(), s.getStatus(), s.getCapacity(), slotStart, slotEnd,
                booked, Math.max(0, s.getCapacity() - booked), processing,
                StatisticsFormulas.ratio(booked, s.getCapacity()));
    }

    /** Trạm được phép xem; stationId chỉ thu hẹp thêm. Trạm ngoài phạm vi cho kết quả rỗng, không lộ dữ liệu. */
    private Set<UUID> allowedStations(UUID actorId, UUID stationId) {
        Set<UUID> ids = switch (scopes.resolve(actorId, RoleName.LOGISTICS_MANAGER)) {
            case OrderScope.Stations s -> s.stationIds();
            case OrderScope.Own o -> Set.<UUID>of();
            case OrderScope.Nothing n -> Set.<UUID>of();
        };
        if (stationId == null) {
            return ids;
        }
        return ids.contains(stationId) ? Set.of(stationId) : Set.of();
    }

    private static Map<UUID, Long> toMap(List<StationPackageCount> rows) {
        return rows.stream().collect(Collectors.toMap(StationPackageCount::stationId, StationPackageCount::packages));
    }
}
