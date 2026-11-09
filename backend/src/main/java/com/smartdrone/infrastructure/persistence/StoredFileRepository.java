package com.smartdrone.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartdrone.domain.entity.StoredFile;
import com.smartdrone.domain.enums.FileStatus;

public interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    Optional<StoredFile> findByIdAndOwnerId(UUID id, UUID ownerId);

    long countByOwnerIdAndStatusAndExpiresAtAfter(UUID ownerId, FileStatus status, Instant now);

    List<StoredFile> findTop200ByStatusAndExpiresAtBefore(FileStatus status, Instant before);

    List<StoredFile> findTop200ByStatusAndUploadedAtBefore(FileStatus status, Instant before);
}