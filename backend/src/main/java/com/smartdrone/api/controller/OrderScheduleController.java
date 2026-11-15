package com.smartdrone.api.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.common.IfMatchVersion;
import com.smartdrone.application.orders.dto.OrderDetail;
import com.smartdrone.application.orders.dto.ScheduleOrderRequest;
import com.smartdrone.application.scheduling.OrderScheduleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/orders")
@PreAuthorize("hasRole('DISPATCHER')")
@Tag(name = "Order scheduling", description = "4.2 Điều phối viên lên lịch giao hàng và kiểm tra sức chứa trạm")
@SecurityRequirement(name = "bearerAuth")
public class OrderScheduleController {

    private final OrderScheduleService service;

    public OrderScheduleController(OrderScheduleService service) {
        this.service = service;
    }

    @PostMapping("/{id}/schedule")
    @Operation(summary = "Lên lịch đơn APPROVED hoặc giao lại đơn FAILED; bắt buộc If-Match và slotStart")
    public ResponseEntity<OrderDetail> schedule(JwtAuthenticationToken auth, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ScheduleOrderRequest request) {
        OrderDetail detail = service.schedule(UUID.fromString(auth.getName()), id,
                IfMatchVersion.parse(ifMatch), request.slotStart());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ETAG, IfMatchVersion.format(detail.version())).body(detail);
    }
}