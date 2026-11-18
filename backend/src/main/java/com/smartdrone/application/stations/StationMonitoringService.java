package com.smartdrone.application.stations;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.stations.dto.StationConnection;
import com.smartdrone.application.stations.dto.StationMonitoringView;
import com.smartdrone.application.stations.dto.StationPackageCount;
import com.smartdrone.application.stations.dto.StationPackageLoad;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.domain.enums.StationStatus;
import com.smartdrone.infrastructure.persistence.StationLoadRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;

/** 4.6: giám sát trạm (chỉ đọc). Dùng lại dữ liệu trạm của 3.2, không tạo API quản lý trạm mới. */
@Service
public class StationMonitoringService {
    private final StationRepository stations;
    private final StationLoadRepository load;
    private final StaffStationScope scope;

    public StationMonitoringService(StationRepository stations, StationLoadRepository load,
            StaffStationScope scope) {
        this.stations = stations;
        this.load = load;
        this.scope = scope;
    }

    @Transactional(readOnly = true)
    public List<StationMonitoringView> monitor(UUID actorId, RoleName role, StationStatus statusFilter) {
        // null nghĩa là được xem mọi trạm (quản lý); vai trò khác chỉ xem trạm được phân công.
        Set<UUID> allowed = role == RoleName.LOGISTICS_MANAGER ? null : scope.stationIdsFor(actorId, role);

        var waiting = toMap(load.countByPickupStation(StationLoadRules.WAITING_DISPATCH));
        var inbound = toMap(load.countByDropoffStation(StationLoadRules.INBOUND));
        var pickup = toMap(load.countByDropoffStation(StationLoadRules.WAITING_PICKUP));

        return stations.findAll(Sort.by("name", "id")).stream()
                .filter(s -> allowed == null || allowed.contains(s.getId()))
                .filter(s -> statusFilter == null || s.getStatus() == statusFilter)
                .map(s -> view(s, waiting, inbound, pickup))
                .toList();
    }

    private static StationMonitoringView view(Station s, Map<UUID, Long> waiting,
            Map<UUID, Long> inbound, Map<UUID, Long> pickup) {
        long w = waiting.getOrDefault(s.getId(), 0L);
        long i = inbound.getOrDefault(s.getId(), 0L);
        long p = pickup.getOrDefault(s.getId(), 0L);
        return new StationMonitoringView(s.getId(), s.getName(), s.getStatus(), s.getCapacity(),
                StationConnection.noData(), new StationPackageLoad(w, i, p, w + i + p));
    }

    private static Map<UUID, Long> toMap(List<StationPackageCount> rows) {
        return rows.stream().collect(Collectors.toMap(StationPackageCount::stationId, StationPackageCount::packages));
    }
}