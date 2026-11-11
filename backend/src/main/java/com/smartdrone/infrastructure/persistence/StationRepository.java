package com.smartdrone.infrastructure.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartdrone.domain.entity.Station;

public interface StationRepository extends JpaRepository<Station, UUID> {
}