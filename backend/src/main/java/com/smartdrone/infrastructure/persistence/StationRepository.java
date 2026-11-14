package com.smartdrone.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;

import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.enums.StationStatus;

public interface StationRepository extends JpaRepository<Station, UUID> {
    List<Station> findAll(Sort sort);
    boolean existsByIdAndStatus(UUID id, StationStatus status);
}
