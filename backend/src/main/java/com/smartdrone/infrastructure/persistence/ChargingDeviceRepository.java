package com.smartdrone.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartdrone.domain.entity.ChargingDevice;

public interface ChargingDeviceRepository extends JpaRepository<ChargingDevice, UUID> {
    List<ChargingDevice> findByStationIdOrderByCode(UUID stationId);

    List<ChargingDevice> findByStationIdInOrderByCode(Collection<UUID> stationIds);
}
