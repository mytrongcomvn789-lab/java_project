package com.smartdrone.api.controller;

import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import com.smartdrone.application.common.IfMatchVersion;
import com.smartdrone.application.orders.OrderReceiptService;
import com.smartdrone.application.orders.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1")
@PreAuthorize("hasRole('STATION_OPERATOR')")
@Tag(name = "Station receipts", description = "4.5 Nhận kiện tại trạm gửi")
@SecurityRequirement(name = "bearerAuth")
public class OrderReceiptController {
    private final OrderReceiptService service;
    public OrderReceiptController(OrderReceiptService service) { this.service = service; }

    @GetMapping("/station-receipts")
    @Operation(summary = "Đơn SCHEDULED tại trạm gửi được phân công; phân trang và lọc")
    public ResponseEntity<OrderPage> list(JwtAuthenticationToken auth,
            @RequestParam(required = false) String code, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String stationId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(
                UUID.fromString(auth.getName()), OrderListQuery.of(code, "SCHEDULED", from, to, stationId,
                        page, pageSize, sort)));
    }
    @GetMapping("/orders/{id}/receipt-context")
    @Operation(summary = "Chi tiết đơn, mã kiện và lần giao để nhân viên kiểm tra trước khi nhận")
    public ResponseEntity<ReceiptContext> context(JwtAuthenticationToken auth, @PathVariable UUID id) {
        var context = service.context(UUID.fromString(auth.getName()), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.format(context.order().version())).body(context);
    }
    @PostMapping("/orders/{id}/receipts")
    @Operation(summary = "RECEIVE: SCHEDULED → PREPARING; bắt buộc If-Match, attemptId, packageId")
    public ResponseEntity<OrderDetail> receive(JwtAuthenticationToken auth, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody ReceivePackageRequest request) {
        var detail = service.receive(UUID.fromString(auth.getName()), id, IfMatchVersion.parse(ifMatch), request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.format(detail.version())).body(detail);
    }
}
