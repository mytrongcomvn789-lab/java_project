package com.smartdrone.infrastructure.persistence;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.smartdrone.domain.entity.StaffStationAssignment;
import com.smartdrone.domain.enums.RoleName;

public interface StaffStationAssignmentRepository extends JpaRepository<StaffStationAssignment, UUID> {
    @Query("""
            select a.station.id from StaffStationAssignment a
            where a.staff.id = :staffId and a.staff.enabled = true and a.staff.role.name = :role
            """)
    Set<UUID> findStationIds(@Param("staffId") UUID staffId, @Param("role") RoleName role);

    boolean existsByStaff_IdAndStation_Id(UUID staffId, UUID stationId);
}
