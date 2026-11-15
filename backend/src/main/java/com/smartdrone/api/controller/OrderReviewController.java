package com.smartdrone.api.controller;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import com.smartdrone.application.common.IfMatchVersion;
import com.smartdrone.application.orders.OrderReviewService;
import com.smartdrone.application.orders.dto.OrderDetail;
import com.smartdrone.application.orders.dto.RejectOrderRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/orders")
@PreAuthorize("hasRole('DISPATCHER')")
@Tag(name = "Order review", description = "4.1 Điều phối viên duyệt hoặc từ chối đơn trong phạm vi trạm")
@SecurityRequirement(name = "bearerAuth")
public class OrderReviewController {
    private final OrderReviewService service;
    public OrderReviewController(OrderReviewService service) { this.service = service; }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Duyệt đơn PENDING; bắt buộc If-Match")
    public ResponseEntity<OrderDetail> approve(JwtAuthenticationToken auth, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return response(service.approve(UUID.fromString(auth.getName()), id, IfMatchVersion.parse(ifMatch)));
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Từ chối đơn PENDING; bắt buộc If-Match và lý do")
    public ResponseEntity<OrderDetail> reject(JwtAuthenticationToken auth, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody RejectOrderRequest request) {
        return response(service.reject(UUID.fromString(auth.getName()), id,
                IfMatchVersion.parse(ifMatch), request.reason()));
    }

    private ResponseEntity<OrderDetail> response(OrderDetail detail) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ETAG, IfMatchVersion.format(detail.version())).body(detail);
    }
}
