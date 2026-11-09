package com.smartdrone.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import com.smartdrone.domain.entity.Address;
import jakarta.persistence.LockModeType;

public interface AddressRepository extends JpaRepository<Address, UUID> {
    Page<Address> findByOwnerIdAndDeletedFalse(UUID ownerId, Pageable pageable);
    Optional<Address> findByIdAndOwnerIdAndDeletedFalse(UUID id, UUID ownerId);

    // Serialize update/delete so a concurrent stale edit cannot resurrect an archived row.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Address a where a.id = :id and a.owner.id = :ownerId and a.deleted = false")
    Optional<Address> findActiveForUpdate(@Param("id") UUID id, @Param("ownerId") UUID ownerId);
}
