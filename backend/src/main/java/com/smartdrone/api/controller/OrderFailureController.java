package com.smartdrone.api.controller;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import com.smartdrone.application.common.IfMatchVersion;
import com.smartdrone.application.orders.OrderFailureService;
import com.smartdrone.application.orders.dto.FailOrderRequest;
import com.smartdrone.application.orders.dto.OrderDetail;
import com.smartdrone.application.orders.dto.ReturnOrderRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/orders")
@PreAuthorize("hasRole('DISPATCHER')")
@Tag(name = "Order failure", description = "4.8 Điều phối viên báo giao thất bại và bắt đầu hoàn hàng")
@SecurityRequirement(name = "bearerAuth")
public class OrderFailureController {
    private final OrderFailureService service;
    public OrderFailureController(OrderFailureService service) { this.service = service; }

    @PostMapping("/{id}/fail")
    @Operation(summary = "FAIL: SCHEDULED/PREPARING/IN_TRANSIT/AWAITING_PICKUP → FAILED; bắt buộc If-Match và reasonCode")
    public ResponseEntity<OrderDetail> fail(JwtAuthenticationToken auth, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody FailOrderRequest request) {
        return response(service.fail(UUID.fromString(auth.getName()), id, IfMatchVersion.parse(ifMatch),
                request.reasonCode(), request.note()));
    }

    @PostMapping("/{id}/return")
    @Operation(summary = "START_RETURN: FAILED → RETURNING; bắt buộc If-Match và lý do")
    public ResponseEntity<OrderDetail> startReturn(JwtAuthenticationToken auth, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ReturnOrderRequest request) {
        return response(service.startReturn(UUID.fromString(auth.getName()), id,
                IfMatchVersion.parse(ifMatch), request.reason()));
    }

    private ResponseEntity<OrderDetail> response(OrderDetail detail) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ETAG, IfMatchVersion.format(detail.version())).body(detail);
    }
}
