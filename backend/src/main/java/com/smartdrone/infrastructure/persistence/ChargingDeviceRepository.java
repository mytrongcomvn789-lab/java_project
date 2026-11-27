package com.smartdrone.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartdrone.domain.entity.ChargingDevice;

public interface ChargingDeviceRepository extends JpaRepository<ChargingDevice, UUID> {
    List<ChargingDevice> findByStationIdOrderByCode(UUID stationId);

    Optional<ChargingDevice> findByDroneId(UUID droneId);

    List<ChargingDevice> findByStationIdInOrderByCode(Collection<UUID> stationIds);
}
