package com.smartdrone.api.controller;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import com.smartdrone.application.common.IfMatchVersion;
import com.smartdrone.application.orders.OrderReturnService;
import com.smartdrone.application.orders.dto.DeliveryProgressRequest;
import com.smartdrone.application.orders.dto.OrderDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/orders")
@PreAuthorize("hasRole('STATION_OPERATOR')")
@Tag(name = "Order return", description = "4.8 Nhân viên trạm gửi xác nhận đã nhận hàng hoàn")
@SecurityRequirement(name = "bearerAuth")
public class OrderReturnController {
    private final OrderReturnService service;
    public OrderReturnController(OrderReturnService service) { this.service = service; }

    @PostMapping("/{id}/complete-return")
    @Operation(summary = "COMPLETE_RETURN: RETURNING → RETURNED; nhân viên trạm gửi; bắt buộc If-Match")
    public ResponseEntity<OrderDetail> completeReturn(JwtAuthenticationToken auth, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody(required = false) DeliveryProgressRequest request) {
        var detail = service.completeReturn(UUID.fromString(auth.getName()), id,
                IfMatchVersion.parse(ifMatch), request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ETAG, IfMatchVersion.format(detail.version())).body(detail);
    }
}
