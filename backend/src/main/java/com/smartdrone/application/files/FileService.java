package com.smartdrone.application.files;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.files.dto.DownloadLinkResponse;
import com.smartdrone.application.files.dto.FileView;
import com.smartdrone.application.files.dto.UploadTicketRequest;
import com.smartdrone.application.files.dto.UploadTicketResponse;
import com.smartdrone.domain.entity.StoredFile;
import com.smartdrone.domain.enums.FilePurpose;
import com.smartdrone.domain.enums.FileStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.StoredFileRepository;

// Task 3.3. Các hàm cố ý KHÔNG @Transactional: trạng thái REJECTED phải được lưu trước khi ném lỗi.
@Service
public class FileService {
    private static final Logger log = LoggerFactory.getLogger(FileService.class);
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

    private final StoredFileRepository files;
    private final ObjectStorage storage;
    private final StorageProperties props;
    private final Clock clock;
    private final Map<String, FileResourceAccessPolicy> policies;

    public FileService(StoredFileRepository files, ObjectStorage storage, StorageProperties props,
                       Clock clock, List<FileResourceAccessPolicy> policyBeans) {
        this.files = files;
        this.storage = storage;
        this.props = props;
        this.clock = clock;
        this.policies = policyBeans.stream()
                .collect(Collectors.toMap(FileResourceAccessPolicy::resourceType, Function.identity()));
    }

    /** Cấp quyền tải lên có hạn. Key do server sinh; client không được chọn. */
    public UploadTicketResponse createUploadTicket(UUID actorId, RoleName role, UploadTicketRequest request) {
        if (!request.purpose().allows(role)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        String type = normalize(request.contentType());
        if (!props.allowedContentTypes().contains(type) || !EXTENSIONS.containsKey(type)) {
            throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        if (request.sizeBytes() > props.maxFileSizeBytes()) {
            throw new ApiException(ErrorCode.FILE_TOO_LARGE,
                    "Tệp vượt quá giới hạn " + props.maxFileSizeBytes() + " byte");
        }
        Instant now = clock.instant();
        if (files.countByOwnerIdAndStatusAndExpiresAtAfter(actorId, FileStatus.PENDING_UPLOAD, now)
                >= props.maxPendingPerUser()) {
            throw new ApiException(ErrorCode.UPLOAD_LIMIT_REACHED);
        }
        Instant expiresAt = now.plus(props.uploadTicketTtl());
        var date = now.atOffset(ZoneOffset.UTC);
        String key = "%s/%04d/%02d/%s.%s".formatted(request.purpose().keyPrefix(),
                date.getYear(), date.getMonthValue(), UUID.randomUUID(), EXTENSIONS.get(type));

        StoredFile file = files.save(new StoredFile(actorId, key, request.purpose(), type, expiresAt, now));
        ObjectStorage.PresignedUpload upload = storage.presignUpload(key, type, props.maxFileSizeBytes(), expiresAt);
        log.info("FILE_UPLOAD_TICKET_CREATED actorId={} fileId={} purpose={}", actorId, file.getId(), file.getPurpose());
        return new UploadTicketResponse(file.getId(),
                new UploadTicketResponse.Upload(upload.url(), "POST", "file", upload.fields()),
                expiresAt, props.maxFileSizeBytes());
    }

    /** Xác nhận đã upload: kiểm tra object tồn tại, dung lượng và loại thật theo nội dung. */
    public FileView completeUpload(UUID actorId, UUID fileId) {
        StoredFile file = files.findByIdAndOwnerId(fileId, actorId).orElseThrow(FileService::notFound);
        if (file.getStatus() == FileStatus.READY || file.getStatus() == FileStatus.ATTACHED) {
            return FileView.of(file); // gọi lặp lại vẫn an toàn
        }
        if (file.getStatus() != FileStatus.PENDING_UPLOAD) {
            throw new ApiException(ErrorCode.FILE_INVALID_STATE);
        }
        var info = storage.stat(file.getObjectKey()).orElseThrow(() -> new ApiException(ErrorCode.FILE_NOT_UPLOADED));
        if (info.size() <= 0 || info.size() > props.maxFileSizeBytes()) {
            reject(file);
            throw new ApiException(ErrorCode.FILE_TOO_LARGE);
        }
        String real = ImageSignature.detect(storage.readHead(file.getObjectKey(), ImageSignature.HEAD_BYTES));
        if (real == null || !props.allowedContentTypes().contains(real) || !real.equals(file.getContentType())) {
            reject(file);
            throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED);
        }
        file.markReady(real, info.size(), clock.instant());
        log.info("FILE_UPLOAD_COMPLETED actorId={} fileId={} size={}", actorId, file.getId(), info.size());
        return FileView.of(files.save(file));
    }

    /** Cấp link tải có hạn. Kiểm tra quyền MỖI LẦN; không có quyền thì trả 404 để không lộ fileId có tồn tại. */
    public DownloadLinkResponse createDownloadLink(UUID actorId, RoleName role, UUID fileId) {
        StoredFile file = files.findById(fileId).filter(f -> canView(actorId, role, f))
                .orElseThrow(FileService::notFound);
        if (file.getStatus() != FileStatus.READY && file.getStatus() != FileStatus.ATTACHED) {
            throw new ApiException(ErrorCode.FILE_INVALID_STATE);
        }
        Instant expiresAt = clock.instant().plus(props.downloadLinkTtl());
        String url = storage.presignDownload(file.getObjectKey(), file.getContentType(), props.downloadLinkTtl());
        log.info("FILE_DOWNLOAD_LINK_ISSUED actorId={} fileId={}", actorId, fileId);
        return new DownloadLinkResponse(file.getId(), url, expiresAt);
    }

    /**
     * Dành cho module đơn hàng/xác nhận giao (3.1, 3.2, 3.4) gọi khi gắn ảnh vào tài nguyên.
     * Chỉ gắn được tệp của chính người gọi, đã READY, đúng mục đích, và object còn tồn tại.
     */
    public void attach(UUID actorId, UUID fileId, FilePurpose expectedPurpose, String resourceType, UUID resourceId) {
        StoredFile file = files.findByIdAndOwnerId(fileId, actorId).orElseThrow(FileService::notFound);
        if (file.getPurpose() != expectedPurpose) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Tệp không dùng đúng mục đích");
        }
        if (file.getStatus() == FileStatus.ATTACHED) {
            if (resourceType.equals(file.getResourceType()) && resourceId.equals(file.getResourceId())) return;
            throw new ApiException(ErrorCode.FILE_INVALID_STATE, "Tệp đã được gắn vào tài nguyên khác");
        }
        if (file.getStatus() != FileStatus.READY) {
            throw new ApiException(ErrorCode.FILE_INVALID_STATE, "Tệp chưa được xác nhận tải lên");
        }
        if (storage.stat(file.getObjectKey()).isEmpty()) {
            throw new ApiException(ErrorCode.FILE_NOT_UPLOADED);
        }
        file.attachTo(resourceType, resourceId, clock.instant());
        files.save(file);
    }

    private boolean canView(UUID actorId, RoleName role, StoredFile file) {
        if (file.getOwnerId().equals(actorId)) return true;
        if (file.getResourceType() == null || file.getResourceId() == null) return false;
        FileResourceAccessPolicy policy = policies.get(file.getResourceType());
        return policy != null && policy.canView(actorId, role, file.getResourceId());
    }

    private void reject(StoredFile file) {
        try {
            storage.remove(file.getObjectKey());
        } finally {
            file.markRejected();
            files.save(file);
        }
    }

    private static String normalize(String contentType) {
        String base = contentType.split(";", 2)[0];
        return base.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static ApiException notFound() {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
    }
}