package com.smartdrone.application.files.dto;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.entity.StoredFile;
import com.smartdrone.domain.enums.FilePurpose;
import com.smartdrone.domain.enums.FileStatus;

public record FileView(UUID id, FilePurpose purpose, FileStatus status, String contentType,
                       Long sizeBytes, Instant createdAt) {
    public static FileView of(StoredFile f) {
        return new FileView(f.getId(), f.getPurpose(), f.getStatus(), f.getContentType(),
                f.getSizeBytes(), f.getCreatedAt());
    }
}