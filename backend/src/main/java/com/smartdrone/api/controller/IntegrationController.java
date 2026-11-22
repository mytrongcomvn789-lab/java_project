package com.smartdrone.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.integration.IntegrationQueryService;
import com.smartdrone.application.integration.dto.IntegrationOrderStatus;
import com.smartdrone.application.integration.dto.IntegrationStationView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Dành cho HỆ THỐNG NGOÀI, xác thực bằng header X-Api-Key (xem IntegrationPolicy, không phải RbacPolicy).
 * Cố ý KHÔNG có @PreAuthorize: quyền ở đây là scope của API key, được quyết định ở SecurityConfig/IntegrationPolicy,
 * không phải vai trò người dùng JWT. Không trộn route này vào các controller /v1/orders, /v1/stations hiện có.
 */
@RestController
@RequestMapping("/v1/integration")
@Tag(name = "Integration", description = "5.4: endpoint cho hệ thống ngoài, xác thực bằng API key (không dùng JWT)")
@SecurityRequirement(name = "apiKeyAuth")
public class IntegrationController {
    private final IntegrationQueryService service;

    public IntegrationController(IntegrationQueryService service) {
        this.service = service;
    }

    @GetMapping("/orders/{id}/status")
    @Operation(summary = "Trạng thái đơn hàng (tối thiểu, không có thông tin người nhận) — cần scope ORDERS_READ")
    public ResponseEntity<IntegrationOrderStatus> orderStatus(@PathVariable UUID id) {
        return noStore(service.orderStatus(id));
    }

    @GetMapping("/stations")
    @Operation(summary = "Danh sách trạm — cần scope STATIONS_READ")
    public ResponseEntity<List<IntegrationStationView>> stations() {
        return noStore(service.stations());
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(value);
    }
}