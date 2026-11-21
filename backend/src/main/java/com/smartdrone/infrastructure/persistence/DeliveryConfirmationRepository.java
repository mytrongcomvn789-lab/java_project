package com.smartdrone.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartdrone.domain.entity.DeliveryConfirmation;

public interface DeliveryConfirmationRepository extends JpaRepository<DeliveryConfirmation, UUID> {
    Optional<DeliveryConfirmation> findByOrderId(UUID orderId);
}