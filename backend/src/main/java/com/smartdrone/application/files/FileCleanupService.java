package com.smartdrone.application.files;

import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.smartdrone.domain.entity.StoredFile;
import com.smartdrone.domain.enums.FileStatus;
import com.smartdrone.infrastructure.persistence.StoredFileRepository;

// Dọn: (1) ticket hết hạn mà chưa xác nhận; (2) tệp READY quá hạn mà chưa gắn vào đơn.
// KHÔNG đụng tới tệp ATTACHED (chính sách giữ ảnh: xem docs/08-deployment/minio-storage.md).
@Component
public class FileCleanupService {
    private static final Logger log = LoggerFactory.getLogger(FileCleanupService.class);

    private final StoredFileRepository files;
    private final ObjectStorage storage;
    private final StorageProperties props;
    private final Clock clock;

    public FileCleanupService(StoredFileRepository files, ObjectStorage storage, StorageProperties props, Clock clock) {
        this.files = files;
        this.storage = storage;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.storage.cleanup-interval:PT10M}", initialDelayString = "PT1M")
    public void cleanup() {
        if (!props.isConfigured()) return;
        Instant now = clock.instant();
        int expired = 0;
        int deleted = 0;
        for (StoredFile f : files.findTop200ByStatusAndExpiresAtBefore(FileStatus.PENDING_UPLOAD, now.minus(props.pendingGrace()))) {
            if (removeQuietly(f)) { f.markExpired(); files.save(f); expired++; }
        }
        for (StoredFile f : files.findTop200ByStatusAndUploadedAtBefore(FileStatus.READY, now.minus(props.unattachedRetention()))) {
            if (removeQuietly(f)) { f.markDeleted(); files.save(f); deleted++; }
        }
        if (expired + deleted > 0) log.info("FILE_CLEANUP expired={} deleted={}", expired, deleted);
    }

    private boolean removeQuietly(StoredFile f) {
        try {
            storage.remove(f.getObjectKey()); // xóa object không tồn tại vẫn thành công
            return true;
        } catch (RuntimeException ex) {
            log.warn("Dọn tệp {} thất bại, sẽ thử lại lần sau", f.getId());
            return false;
        }
    }
}