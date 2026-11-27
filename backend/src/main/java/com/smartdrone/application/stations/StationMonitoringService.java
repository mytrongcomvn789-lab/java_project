package com.smartdrone.application.stations;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.charging.StationChargingService;
import com.smartdrone.application.charging.StationChargingService.StationChargingOverview;
import com.smartdrone.application.scheduling.ScheduleProperties;
import com.smartdrone.application.scheduling.ScheduleSlotPolicy;
import com.smartdrone.application.stations.dto.StationConnection;
import com.smartdrone.application.stations.dto.StationMonitoringView;
import com.smartdrone.application.stations.dto.StationPackageCount;
import com.smartdrone.application.stations.dto.StationPackageLoad;
import com.smartdrone.application.stations.dto.StationSlotUsage;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.domain.enums.StationStatus;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;
import com.smartdrone.infrastructure.persistence.StationLoadRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;

/** 4.6: giám sát trạm (chỉ đọc). Dùng lại dữ liệu trạm của 3.2 và lịch giao của 4.2, không tự tính đặt chỗ. */
@Service
public class StationMonitoringService {
    private final StationRepository stations;
    private final StationLoadRepository load;
    private final StaffStationScope scope;
    private final DeliveryAttemptRepository attempts;
    private final ScheduleProperties schedule;
    private final ScheduleSlotPolicy slotPolicy;
    private final Clock clock;
    private final StationChargingService charging;

    public StationMonitoringService(StationRepository stations, StationLoadRepository load,
            StaffStationScope scope, DeliveryAttemptRepository attempts, ScheduleProperties schedule,
            ScheduleSlotPolicy slotPolicy, Clock clock, StationChargingService charging) {
        this.stations = stations;
        this.load = load;
        this.scope = scope;
        this.attempts = attempts;
        this.schedule = schedule;
        this.slotPolicy = slotPolicy;
        this.clock = clock;
        this.charging = charging;
    }

    @Transactional(readOnly = true)
    public List<StationMonitoringView> monitor(UUID actorId, RoleName role, StationStatus statusFilter) {
        // null nghĩa là được xem mọi trạm (quản lý); vai trò khác chỉ xem trạm được phân công.
        Set<UUID> allowed = role == RoleName.LOGISTICS_MANAGER ? null : scope.stationIdsFor(actorId, role);

        var scheduled = toMap(load.countByPickupStation(StationLoadRules.SCHEDULED));
        var preparing = toMap(load.countByPickupStation(StationLoadRules.PREPARING));
        var inbound = toMap(load.countByDropoffStation(StationLoadRules.INBOUND));
        var waitingPickup = toMap(load.countByDropoffStation(StationLoadRules.WAITING_PICKUP));

        Instant slotStart = currentSlotStart();
        Instant slotEnd = slotPolicy.slotEnd(slotStart);

        var visible = stations.findAll(Sort.by("name", "id")).stream()
                .filter(s -> allowed == null || allowed.contains(s.getId()))
                .filter(s -> statusFilter == null || s.getStatus() == statusFilter)
                .toList();
        var chargers = charging.overview(visible.stream().map(Station::getId).toList());
        return visible.stream()
                .map(s -> view(s, scheduled, preparing, inbound, waitingPickup, slotStart, slotEnd,
                        chargers.get(s.getId())))
                .toList();
    }

    private StationMonitoringView view(Station s, Map<UUID, Long> scheduled, Map<UUID, Long> preparing,
            Map<UUID, Long> inbound, Map<UUID, Long> waitingPickup, Instant slotStart, Instant slotEnd,
            StationChargingOverview chargerData) {
        long sc = scheduled.getOrDefault(s.getId(), 0L);
        long pr = preparing.getOrDefault(s.getId(), 0L);
        long in = inbound.getOrDefault(s.getId(), 0L);
        long wp = waitingPickup.getOrDefault(s.getId(), 0L);
        // Chỗ đang giữ lấy từ truy vấn của 4.2, cùng nguồn với kiểm tra sức chứa khi lên lịch.
        long used = attempts.countActiveOverlapping(s.getId(), slotStart, slotEnd);
        long free = Math.max(0, s.getCapacity() - used);
        return new StationMonitoringView(s.getId(), s.getName(), s.getAddress(), s.getStatus(), s.getCapacity(),
                chargerData == null ? StationConnection.noData() : chargerData.connection(),
                new StationSlotUsage(slotStart, slotEnd, used, free),
                new StationPackageLoad(sc, pr, in, wp, pr + wp, sc + pr + in + wp),
                chargerData == null ? List.of() : chargerData.chargers());
    }

    /** Đầu khung giờ hiện tại, cùng lưới với 4.2 (bội số của độ dài slot tính từ epoch). */
    private Instant currentSlotStart() {
        long step = schedule.slotLength().toSeconds();
        long now = Instant.now(clock).getEpochSecond();
        return Instant.ofEpochSecond(Math.floorDiv(now, step) * step);
    }

    private static Map<UUID, Long> toMap(List<StationPackageCount> rows) {
        return rows.stream().collect(Collectors.toMap(StationPackageCount::stationId, StationPackageCount::packages));
    }
}