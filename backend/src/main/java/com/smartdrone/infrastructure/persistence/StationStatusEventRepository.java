package com.smartdrone.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartdrone.domain.entity.StationStatusEvent;

public interface StationStatusEventRepository extends JpaRepository<StationStatusEvent, UUID> {
    List<StationStatusEvent> findByStationIdOrderByOccurredAtAsc(UUID stationId);
}
