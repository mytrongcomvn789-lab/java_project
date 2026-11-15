package com.smartdrone.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.enums.StationStatus;

import jakarta.persistence.LockModeType;

public interface StationRepository extends JpaRepository<Station, UUID> {
    List<Station> findAll(Sort sort);
    boolean existsByIdAndStatus(UUID id, StationStatus status);

    // Khoa cac dong tram den het transaction, luon theo thu tu id de hai dieu phoi vien
    // dat cung luc khong khoa cheo nhau (deadlock). Dung khi len lich de dem cho khong bi vuot.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Station s where s.id in :ids order by s.id")
    List<Station> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);
}