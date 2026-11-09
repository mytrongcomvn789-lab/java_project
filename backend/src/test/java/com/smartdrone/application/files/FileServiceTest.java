package com.smartdrone.application.files;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.files.dto.UploadTicketRequest;
import com.smartdrone.domain.entity.StoredFile;
import com.smartdrone.domain.enums.FilePurpose;
import com.smartdrone.domain.enums.FileStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.StoredFileRepository;

class FileServiceTest {
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-08T03:00:00Z"), ZoneOffset.UTC);
    private final StorageProperties props = new StorageProperties("http://localhost:9000", null, "k", "s",
            "b", "us-east-1", true, 5 * 1024 * 1024, Duration.ofMinutes(5), Duration.ofMinutes(5),
            List.of("image/jpeg", "image/png", "image/webp"), 2, Duration.ofHours(1), Duration.ofHours(24));

    private final Map<UUID, StoredFile> db = new HashMap<>();
    private final Map<String, byte[]> objects = new HashMap<>(); // key -> nội dung (giả lập kho)
    private StoredFileRepository repo;
    private FileService service;
    private FileResourceAccessPolicy orderPolicy;
    private final UUID customerA = UUID.randomUUID(), customerB = UUID.randomUUID();
    private final UUID dispatcher = UUID.randomUUID(), orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(StoredFileRepository.class);
        Mockito.when(repo.save(Mockito.any(StoredFile.class))).thenAnswer(inv -> {
            StoredFile f = inv.getArgument(0);
            if (f.getId() == null) {
                try {
                    var id = StoredFile.class.getDeclaredField("id");
                    id.setAccessible(true);
                    id.set(f, UUID.randomUUID());
                } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
            }
            db.put(f.getId(), f);
            return f;
        });
        Mockito.when(repo.findById(Mockito.any(UUID.class))).thenAnswer(inv -> Optional.ofNullable(db.get(inv.<UUID>getArgument(0))));
        Mockito.when(repo.findByIdAndOwnerId(Mockito.any(), Mockito.any())).thenAnswer(inv ->
                Optional.ofNullable(db.get(inv.<UUID>getArgument(0)))
                        .filter(f -> f.getOwnerId().equals(inv.<UUID>getArgument(1))));
        Mockito.when(repo.countByOwnerIdAndStatusAndExpiresAtAfter(Mockito.any(), Mockito.any(), Mockito.any()))
                .thenAnswer(inv -> db.values().stream().filter(f -> f.getOwnerId().equals(inv.<UUID>getArgument(0))
                        && f.getStatus() == inv.<FileStatus>getArgument(1)).count());

        ObjectStorage storage = new ObjectStorage() {
            public void ensureBucket() { }
            public PresignedUpload presignUpload(String k, String c, long m, Instant e) { return new PresignedUpload("http://x/b", Map.of("key", k)); }
            public String presignDownload(String k, String c, Duration t) { return "http://x/b/" + k + "?sig"; }
            public Optional<StoredObjectInfo> stat(String k) { return Optional.ofNullable(objects.get(k)).map(b -> new StoredObjectInfo(b.length)); }
            public byte[] readHead(String k, int n) { byte[] b = objects.get(k); return java.util.Arrays.copyOf(b, Math.min(n, b.length)); }
            public void remove(String k) { objects.remove(k); }
        };
        orderPolicy = new FileResourceAccessPolicy() {
            public String resourceType() { return "ORDER"; }
            public boolean canView(UUID actor, RoleName role, UUID resourceId) {
                return resourceId.equals(orderId) && (actor.equals(customerA) || actor.equals(dispatcher));
            }
        };
        service = new FileService(repo, storage, props, clock, new ArrayList<>(List.of(orderPolicy)));
    }

    private UUID uploadAndComplete(UUID owner, byte[] content) {
        var ticket = service.createUploadTicket(owner, RoleName.CUSTOMER,
                new UploadTicketRequest(FilePurpose.PACKAGE_PHOTO, "image/png", 100L));
        objects.put(db.get(ticket.fileId()).getObjectKey(), content);
        service.completeUpload(owner, ticket.fileId());
        return ticket.fileId();
    }

    @Test
    void clientCannotChooseKeyAndKeyIsNotExposed() {
        var ticket = service.createUploadTicket(customerA, RoleName.CUSTOMER,
                new UploadTicketRequest(FilePurpose.PACKAGE_PHOTO, "image/png", 100L));
        String key = db.get(ticket.fileId()).getObjectKey();
        assertTrue(key.startsWith("package-photos/"));
        assertFalse(key.contains(customerA.toString()));
        assertFalse(ticket.toString().contains("sig=secret"));
    }

    @Test
    void roleMustMatchPurposeAndTypeAndSizeAreChecked() {
        assertEquals(ErrorCode.FORBIDDEN, error(() -> service.createUploadTicket(customerA, RoleName.CUSTOMER,
                new UploadTicketRequest(FilePurpose.CONFIRMATION_PHOTO, "image/png", 100L))));
        assertEquals(ErrorCode.FILE_TYPE_NOT_ALLOWED, error(() -> service.createUploadTicket(customerA, RoleName.CUSTOMER,
                new UploadTicketRequest(FilePurpose.PACKAGE_PHOTO, "application/pdf", 100L))));
        assertEquals(ErrorCode.FILE_TOO_LARGE, error(() -> service.createUploadTicket(customerA, RoleName.CUSTOMER,
                new UploadTicketRequest(FilePurpose.PACKAGE_PHOTO, "image/png", 6L * 1024 * 1024))));
    }

    @Test
    void pendingTicketsPerUserAreLimited() {
        for (int i = 0; i < 2; i++) {
            service.createUploadTicket(customerA, RoleName.CUSTOMER, new UploadTicketRequest(FilePurpose.PACKAGE_PHOTO, "image/png", 1L));
        }
        assertEquals(ErrorCode.UPLOAD_LIMIT_REACHED, error(() -> service.createUploadTicket(customerA, RoleName.CUSTOMER,
                new UploadTicketRequest(FilePurpose.PACKAGE_PHOTO, "image/png", 1L))));
    }

    @Test
    void fakeContentTypeIsRejectedByMagicBytesAndNeverBecomesReady() {
        var ticket = service.createUploadTicket(customerA, RoleName.CUSTOMER,
                new UploadTicketRequest(FilePurpose.PACKAGE_PHOTO, "image/png", 100L));
        objects.put(db.get(ticket.fileId()).getObjectKey(), "<html>not an image</html>".getBytes());
        assertEquals(ErrorCode.FILE_TYPE_NOT_ALLOWED, error(() -> service.completeUpload(customerA, ticket.fileId())));
        assertEquals(FileStatus.REJECTED, db.get(ticket.fileId()).getStatus());
        assertTrue(objects.isEmpty(), "object phải bị xóa");
        assertThrows(ApiException.class, () -> service.attach(customerA, ticket.fileId(), FilePurpose.PACKAGE_PHOTO, "ORDER", orderId));
    }

    @Test
    void notUploadedFileCannotBeCompletedOrAttached() {
        var ticket = service.createUploadTicket(customerA, RoleName.CUSTOMER,
                new UploadTicketRequest(FilePurpose.PACKAGE_PHOTO, "image/png", 100L));
        assertEquals(ErrorCode.FILE_NOT_UPLOADED, error(() -> service.completeUpload(customerA, ticket.fileId())));
        assertEquals(ErrorCode.FILE_INVALID_STATE, error(() ->
                service.attach(customerA, ticket.fileId(), FilePurpose.PACKAGE_PHOTO, "ORDER", orderId)));
    }

    @Test
    void attachFailsIfObjectDisappeared() {
        UUID id = uploadAndComplete(customerA, PNG);
        objects.clear();
        assertEquals(ErrorCode.FILE_NOT_UPLOADED, error(() ->
                service.attach(customerA, id, FilePurpose.PACKAGE_PHOTO, "ORDER", orderId)));
    }

    @Test
    void userAcannotGetLinkOfUserBByChangingFileId() {
        UUID fileOfB = uploadAndComplete(customerB, PNG);
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error(() -> service.createDownloadLink(customerA, RoleName.CUSTOMER, fileOfB)));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error(() -> service.createDownloadLink(dispatcher, RoleName.DISPATCHER, fileOfB)));
        assertNotNull(service.createDownloadLink(customerB, RoleName.CUSTOMER, fileOfB).url());
    }

    @Test
    void linkedFileFollowsResourcePolicyAndFailsClosedWithoutPolicy() {
        UUID id = uploadAndComplete(customerB, PNG);
        service.attach(customerB, id, FilePurpose.PACKAGE_PHOTO, "ORDER", orderId);
        // Policy cho phép dispatcher/customerA của đơn; customer lạ vẫn bị chặn.
        assertNotNull(service.createDownloadLink(dispatcher, RoleName.DISPATCHER, id).url());
        UUID stranger = UUID.randomUUID();
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error(() -> service.createDownloadLink(stranger, RoleName.STATION_OPERATOR, id)));

        // Gắn vào loại tài nguyên chưa có policy => chỉ chủ file xem được.
        UUID id2 = uploadAndComplete(customerB, PNG);
        service.attach(customerB, id2, FilePurpose.PACKAGE_PHOTO, "UNKNOWN", orderId);
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, error(() -> service.createDownloadLink(dispatcher, RoleName.DISPATCHER, id2)));
    }

    @Test
    void attachedFileCannotBeReattachedElsewhere() {
        UUID id = uploadAndComplete(customerA, PNG);
        service.attach(customerA, id, FilePurpose.PACKAGE_PHOTO, "ORDER", orderId);
        service.attach(customerA, id, FilePurpose.PACKAGE_PHOTO, "ORDER", orderId); // lặp lại: OK
        assertEquals(ErrorCode.FILE_INVALID_STATE, error(() ->
                service.attach(customerA, id, FilePurpose.PACKAGE_PHOTO, "ORDER", UUID.randomUUID())));
    }

    private static ErrorCode error(Runnable r) {
        return assertThrows(ApiException.class, r::run).getErrorCode();
    }
}