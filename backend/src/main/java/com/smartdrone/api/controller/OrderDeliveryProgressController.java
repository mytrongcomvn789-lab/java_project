package com.smartdrone.api.controller;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import com.smartdrone.application.common.IfMatchVersion;
import com.smartdrone.application.orders.OrderDeliveryProgressService;
import com.smartdrone.application.orders.dto.DeliveryProgressRequest;
import com.smartdrone.application.orders.dto.OrderDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1")
@PreAuthorize("hasRole('STATION_OPERATOR')")
@Tag(name = "Delivery progress", description = "4.4 Xuất phát (trạm gửi) và đến trạm (trạm nhận)")
@SecurityRequirement(name = "bearerAuth")
public class OrderDeliveryProgressController {
    private final OrderDeliveryProgressService service;
    public OrderDeliveryProgressController(OrderDeliveryProgressService service) { this.service = service; }

    @PostMapping("/orders/{id}/dispatch")
    @Operation(summary = "DISPATCH: PREPARING → IN_TRANSIT; nhân viên trạm gửi; bắt buộc If-Match")
    public ResponseEntity<OrderDetail> dispatch(JwtAuthenticationToken auth, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody(required = false) DeliveryProgressRequest request) {
        var detail = service.dispatch(UUID.fromString(auth.getName()), id, IfMatchVersion.parse(ifMatch), request);
        return ok(detail);
    }

    @PostMapping("/orders/{id}/arrive")
    @Operation(summary = "ARRIVE: IN_TRANSIT → AWAITING_PICKUP; nhân viên trạm nhận; bắt buộc If-Match")
    public ResponseEntity<OrderDetail> arrive(JwtAuthenticationToken auth, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody(required = false) DeliveryProgressRequest request) {
        var detail = service.arrive(UUID.fromString(auth.getName()), id, IfMatchVersion.parse(ifMatch), request);
        return ok(detail);
    }

    private static ResponseEntity<OrderDetail> ok(OrderDetail detail) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.format(detail.version())).body(detail);
    }
}
