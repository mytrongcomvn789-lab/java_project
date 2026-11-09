package com.smartdrone.domain.entity;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.enums.FilePurpose;
import com.smartdrone.domain.enums.FileStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

// Metadata tệp. objectKey do server sinh và KHÔNG bao giờ trả cho client.
@Entity
@Table(name = "stored_files", indexes = {
        @Index(name = "idx_stored_files_owner", columnList = "owner_id"),
        @Index(name = "idx_stored_files_status", columnList = "status"),
        @Index(name = "idx_stored_files_resource", columnList = "resource_type,resource_id")})
public class StoredFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "object_key", nullable = false, unique = true, length = 255, updatable = false)
    private String objectKey;

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private FilePurpose purpose;

    // Loại client khai báo lúc xin ticket; sau khi xác nhận được ghi đè bằng loại thật đọc từ nội dung.
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FileStatus status = FileStatus.PENDING_UPLOAD;

    @Column(name = "resource_type", length = 40)
    private String resourceType;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt; // hạn của upload ticket

    @Column(name = "uploaded_at")
    private Instant uploadedAt;

    @Column(name = "attached_at")
    private Instant attachedAt;

    protected StoredFile() { }

    public StoredFile(UUID ownerId, String objectKey, FilePurpose purpose, String contentType,
                      Instant expiresAt, Instant now) {
        this.ownerId = ownerId;
        this.objectKey = objectKey;
        this.purpose = purpose;
        this.contentType = contentType;
        this.expiresAt = expiresAt;
        this.createdAt = now;
    }

    public UUID getId() { return id; }
    public String getObjectKey() { return objectKey; }
    public UUID getOwnerId() { return ownerId; }
    public FilePurpose getPurpose() { return purpose; }
    public String getContentType() { return contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public FileStatus getStatus() { return status; }
    public String getResourceType() { return resourceType; }
    public UUID getResourceId() { return resourceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUploadedAt() { return uploadedAt; }

    public void markReady(String verifiedContentType, long size, Instant now) {
        this.contentType = verifiedContentType;
        this.sizeBytes = size;
        this.uploadedAt = now;
        this.status = FileStatus.READY;
    }

    public void attachTo(String type, UUID id, Instant now) {
        this.resourceType = type;
        this.resourceId = id;
        this.attachedAt = now;
        this.status = FileStatus.ATTACHED;
    }

    public void markRejected() { this.status = FileStatus.REJECTED; }
    public void markExpired() { this.status = FileStatus.EXPIRED; }
    public void markDeleted() { this.status = FileStatus.DELETED; }
}