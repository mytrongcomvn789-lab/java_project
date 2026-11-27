package com.smartdrone.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartdrone.domain.entity.DroneAssignment;
import com.smartdrone.domain.enums.AssignmentStatus;

public interface DroneAssignmentRepository extends JpaRepository<DroneAssignment, UUID> {
    Optional<DroneAssignment> findFirstByOrderIdAndStatusIn(UUID orderId, Collection<AssignmentStatus> statuses);

    List<DroneAssignment> findByStatusIn(Collection<AssignmentStatus> statuses);

    List<DroneAssignment> findByOrderIdIn(Collection<UUID> orderIds);
}
