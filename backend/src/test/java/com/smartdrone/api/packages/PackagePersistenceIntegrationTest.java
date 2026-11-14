package com.smartdrone.api.packages;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.files.FileService;
import com.smartdrone.application.packages.PackageService;
import com.smartdrone.application.packages.dto.PackageInput;

// Kiểm tra lưu/đọc thật trên H2 (ánh xạ @ElementCollection + @OrderColumn, ràng buộc unique, @PreAuthorize).
// FileService được mock vì MinIO không thuộc phạm vi 3.4 (đã có MinioFileFlowTest của 3.3).
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:package-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.key=pkg34-test-signing-key-longer-than-32-characters"
})
@ActiveProfiles("h2")
class PackagePersistenceIntegrationTest {
    @Autowired PackageService service;
    @MockitoBean FileService files;

    private PackageInput input(String description, UUID... photos) {
        return new PackageInput(description, 750, 20.0, 15.0, 8.0, List.of(photos));
    }

    @Test @WithMockUser(roles = "CUSTOMER")
    void createReadReplaceKeepsPhotoOrder() {
        UUID customer = UUID.randomUUID(), order = UUID.randomUUID();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();

        service.create(customer, order, input("Hộp A", a, b));
        var read = service.findByOrderId(order).orElseThrow();
        assertEquals("Hộp A", read.description());
        assertEquals(List.of(a, b), read.photoFileIds());

        service.replace(customer, order, input("Hộp B", c, a));
        var updated = service.findByOrderId(order).orElseThrow();
        assertEquals("Hộp B", updated.description());
        assertEquals(List.of(c, a), updated.photoFileIds());
        assertEquals(updated, service.findByOrderIds(List.of(order, UUID.randomUUID())).get(order));
    }

    @Test @WithMockUser(roles = "CUSTOMER")
    void secondPackageForSameOrderIsConflict() {
        UUID customer = UUID.randomUUID(), order = UUID.randomUUID();
        service.create(customer, order, input("Một", UUID.randomUUID()));
        var ex = assertThrows(ApiException.class,
                () -> service.create(customer, order, input("Hai", UUID.randomUUID())));
        assertEquals(409, ex.getStatus().value());
    }

    @Test @WithMockUser(roles = "DISPATCHER")
    void onlyCustomerMayWrite() {
        assertThrows(AccessDeniedException.class,
                () -> service.create(UUID.randomUUID(), UUID.randomUUID(), input("X", UUID.randomUUID())));
    }
}
