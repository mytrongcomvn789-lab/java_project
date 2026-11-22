package com.smartdrone.application.stations;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.admin.AuditService;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.stations.dto.StationInput;
import com.smartdrone.application.stations.dto.StationStatusInput;
import com.smartdrone.application.stations.dto.StationView;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.entity.StationStatusEvent;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.domain.enums.StationStatus;
import com.smartdrone.infrastructure.persistence.StationRepository;
import com.smartdrone.infrastructure.persistence.StationStatusEventRepository;

@Service
public class StationService implements StationDirectory {
    private final StationRepository stations;
    private final StaffStationScope scope;
    private final StationStatusEventRepository events;
    private final AuditService audit;

    public StationService(StationRepository stations, StaffStationScope scope,
            StationStatusEventRepository events, AuditService audit) {
        this.stations = stations;
        this.scope = scope;
        this.events = events;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER','SYSTEM_ADMIN')")
    public List<StationView> list() {
        return stations.findAll(Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id")))
                .stream().map(StationView::from).toList();
    }

    @Transactional
    @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
    public StationView create(StationInput input) {
        var station = stations.saveAndFlush(
                new Station(input.name(), input.latitude(), input.longitude(), input.capacity()));
        audit.recordForCurrentUser("STATION_CREATED", "STATION", station.getId().toString(), describe(input));
        return StationView.from(station);
    }

    @Transactional
    @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
    public StationView update(UUID id, StationInput input) {
        var station = stations.findById(id).orElseThrow(StationService::notFound);
        station.update(input.name(), input.latitude(), input.longitude(), input.capacity());
        var saved = stations.saveAndFlush(station);
        audit.recordForCurrentUser("STATION_UPDATED", "STATION", id.toString(), describe(input));
        return StationView.from(saved);
    }

    @Transactional
    @PreAuthorize("hasAnyRole('LOGISTICS_MANAGER','STATION_OPERATOR')")
    public StationView changeStatus(UUID actorId, RoleName actorRole, UUID id, StationStatusInput input) {
        var station = stations.findById(id).orElseThrow(StationService::notFound);
        // Operator chỉ đổi được trạm mình được phân công; không có dòng phân công thì bị chặn.
        if (actorRole == RoleName.STATION_OPERATOR
                && !scope.stationIdsFor(actorId, actorRole).contains(id)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Bạn chỉ được đổi trạng thái trạm được phân công");
        }
        var from = station.getStatus();
        if (from == input.status()) {
            return StationView.from(station); // không đổi gì thì không ghi audit
        }
        station.changeStatus(input.status());
        var saved = stations.saveAndFlush(station);
        events.save(new StationStatusEvent(id, from, input.status(), actorId, actorRole, Instant.now()));
        audit.record(actorId, "STATION_STATUS_CHANGED", "STATION", id.toString(),
                "status=" + from + "->" + input.status());
        return StationView.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID stationId) {
        return stations.existsByIdAndStatus(stationId, StationStatus.ACTIVE);
    }

    private static String describe(StationInput input) {
        return "name=" + input.name() + ", lat=" + input.latitude() + ", lng=" + input.longitude()
                + ", capacity=" + input.capacity();
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
