package com.smartdrone.application.packages;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.files.FileService;
import com.smartdrone.application.packages.dto.PackageInput;
import com.smartdrone.domain.entity.OrderPackage;
import com.smartdrone.domain.enums.FilePurpose;
import com.smartdrone.infrastructure.persistence.OrderPackageRepository;

class PackageServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-10T03:00:00Z"), ZoneOffset.UTC);
    // Giới hạn trong test độc lập với application.yml
    private final PackageProperties limits = new PackageProperties(5000, 50.0, 40.0, 30.0, 1, 3);
    private final UUID customer = UUID.randomUUID(), order = UUID.randomUUID();
    private OrderPackageRepository repo;
    private FileService files;
    private PackageService service;

    @BeforeEach
    void setUp() {
        repo = mock(OrderPackageRepository.class);
        files = mock(FileService.class);
        when(repo.saveAndFlush(any(OrderPackage.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new PackageService(repo, files, limits, clock);
    }

    private PackageInput input(int weight, double l, double w, double h, UUID... photos) {
        return new PackageInput("Hộp thuốc", weight, l, w, h, List.of(photos));
    }
    private void assertInvalid(PackageInput in, String contains) {
        var ex = assertThrows(ApiException.class, () -> service.create(customer, order, in));
        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
        assertTrue(ex.getMessage().contains(contains), ex.getMessage());
        verifyNoInteractions(files);
        verify(repo, never()).saveAndFlush(any());
    }

    @Test void createStoresPackageAndAttachesPhotosInOrder() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var view = service.create(customer, order, input(800, 20, 15, 8, a, b));
        assertEquals("Hộp thuốc", view.description());
        assertEquals(800, view.weightGrams());
        assertEquals(List.of(a, b), view.photoFileIds());
        var inOrder = inOrder(files);
        inOrder.verify(files).attach(customer, a, FilePurpose.PACKAGE_PHOTO, "ORDER", order);
        inOrder.verify(files).attach(customer, b, FilePurpose.PACKAGE_PHOTO, "ORDER", order);
    }

    @Test void limitsAreInclusiveAndEnforced() {
        UUID p = UUID.randomUUID();
        assertDoesNotThrow(() -> service.create(customer, order, input(5000, 50, 40, 30, p)));
        clearInvocations(files, repo); // lần tạo thành công ở trên đã gọi files/repo; assertInvalid cần mock sạch
        assertInvalid(input(5001, 10, 10, 10, p), "Trọng lượng");
        assertInvalid(input(100, 50.1, 10, 10, p), "Chiều dài");
        assertInvalid(input(100, 10, 40.1, 10, p), "Chiều rộng");
        assertInvalid(input(100, 10, 10, 30.1, p), "Chiều cao");
    }

    @Test void photoCountAndDuplicatesAreChecked() {
        UUID a = UUID.randomUUID();
        assertInvalid(input(100, 10, 10, 10), "ít nhất 1 ảnh");
        assertInvalid(input(100, 10, 10, 10, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), "Tối đa 3 ảnh");
        assertInvalid(input(100, 10, 10, 10, a, a), "trùng");
    }

    @Test void createRejectsSecondPackageForSameOrder() {
        when(repo.existsByOrderId(order)).thenReturn(true);
        var ex = assertThrows(ApiException.class, () -> service.create(customer, order, input(100, 10, 10, 10, UUID.randomUUID())));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        verifyNoInteractions(files);
    }

    @Test void foreignOrInvalidPhotoStopsBeforeSaving() {
        UUID bad = UUID.randomUUID();
        doThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND)).when(files)
                .attach(customer, bad, FilePurpose.PACKAGE_PHOTO, "ORDER", order);
        var ex = assertThrows(ApiException.class, () -> service.create(customer, order, input(100, 10, 10, 10, bad)));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
        verify(repo, never()).saveAndFlush(any());
    }

    @Test void replaceUpdatesFieldsAndPhotoList() {
        UUID keep = UUID.randomUUID(), added = UUID.randomUUID(), removed = UUID.randomUUID();
        var existing = new OrderPackage(order, clock.instant());
        existing.update("Cũ", 100, 1, 1, 1, new ArrayList<>(List.of(keep, removed)), clock.instant());
        when(repo.findForUpdateByOrderId(order)).thenReturn(Optional.of(existing));

        var view = service.replace(customer, order, input(900, 30, 20, 10, added, keep));

        assertEquals("Hộp thuốc", view.description());
        assertEquals(900, view.weightGrams());
        assertEquals(List.of(added, keep), view.photoFileIds());
        verify(files).attach(customer, added, FilePurpose.PACKAGE_PHOTO, "ORDER", order);
        verify(files).attach(customer, keep, FilePurpose.PACKAGE_PHOTO, "ORDER", order);
        verify(files, never()).attach(any(), eq(removed), any(), any(), any());
    }

    @Test void replaceOnOrderWithoutPackageIsNotFound() {
        when(repo.findForUpdateByOrderId(order)).thenReturn(Optional.empty());
        var ex = assertThrows(ApiException.class, () -> service.replace(customer, order, input(100, 10, 10, 10, UUID.randomUUID())));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, ex.getErrorCode());
    }

    @Test void findByOrderIdsMapsByOrderAndSkipsEmptyInput() {
        var other = UUID.randomUUID();
        var p1 = new OrderPackage(order, clock.instant());
        p1.update("A", 100, 1, 1, 1, new ArrayList<>(), clock.instant());
        when(repo.findByOrderIdIn(List.of(order, other))).thenReturn(List.of(p1));
        var map = service.findByOrderIds(List.of(order, other));
        assertEquals(1, map.size());
        assertEquals("A", map.get(order).description());
        assertTrue(service.findByOrderIds(List.of()).isEmpty());
        verify(repo, times(1)).findByOrderIdIn(any());
    }

    @Test void propertiesFailFastWhenLimitsMissing() {
        assertThrows(IllegalStateException.class, () -> new PackageProperties(null, 1.0, 1.0, 1.0, 0, 1));
        assertThrows(IllegalStateException.class, () -> new PackageProperties(1, 0.0, 1.0, 1.0, 0, 1));
        assertThrows(IllegalStateException.class, () -> new PackageProperties(1, 1.0, 1.0, 1.0, 2, 1));
    }
}
