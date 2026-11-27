package com.smartdrone.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartdrone.domain.entity.Drone;
import com.smartdrone.domain.enums.DroneStatus;

public interface DroneRepository extends JpaRepository<Drone, UUID> {
    List<Drone> findByIdIn(Collection<UUID> ids);

    List<Drone> findByStationIdAndStatusOrderByCode(UUID stationId, DroneStatus status);
}
