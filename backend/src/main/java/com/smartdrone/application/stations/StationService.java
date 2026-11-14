package com.smartdrone.application.stations;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.stations.dto.StationInput;
import com.smartdrone.application.stations.dto.StationStatusInput;
import com.smartdrone.application.stations.dto.StationView;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.enums.StationStatus;
import com.smartdrone.infrastructure.persistence.StationRepository;

@Service
public class StationService implements StationDirectory {
    private final StationRepository stations;

    public StationService(StationRepository stations) {
        this.stations = stations;
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
        var station = new Station(input.name(), input.latitude(), input.longitude(), input.capacity());
        return StationView.from(stations.saveAndFlush(station));
    }

    @Transactional
    @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
    public StationView update(UUID id, StationInput input) {
        var station = stations.findById(id).orElseThrow(StationService::notFound);
        station.update(input.name(), input.latitude(), input.longitude(), input.capacity());
        return StationView.from(stations.saveAndFlush(station));
    }

    @Transactional
    @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
    public StationView changeStatus(UUID id, StationStatusInput input) {
        var station = stations.findById(id).orElseThrow(StationService::notFound);
        station.changeStatus(input.status());
        return StationView.from(stations.saveAndFlush(station));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isActive(UUID stationId) {
        return stations.existsByIdAndStatus(stationId, StationStatus.ACTIVE);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
    }
}
