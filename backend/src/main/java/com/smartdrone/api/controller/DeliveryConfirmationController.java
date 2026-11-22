package com.smartdrone.api.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.common.IfMatchVersion;
import com.smartdrone.application.pickup.DeliveryConfirmationQueryService;
import com.smartdrone.application.pickup.DeliveryConfirmationService;
import com.smartdrone.application.pickup.dto.ConfirmDeliveryRequest;
import com.smartdrone.application.pickup.dto.DeliveryConfirmationView;
import com.smartdrone.domain.enums.RoleName;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1")
@Tag(name = "Delivery confirmation", description = "4.7 Xác nhận giao hàng bằng OTP/QR + ảnh")
@SecurityRequirement(name = "bearerAuth")
public class DeliveryConfirmationController {
    private final DeliveryConfirmationService service;
    private final DeliveryConfirmationQueryService queries;

    public DeliveryConfirmationController(DeliveryConfirmationService service,
            DeliveryConfirmationQueryService queries) {
        this.service = service;
        this.queries = queries;
    }

    @PostMapping("/orders/{id}/confirmations")
    @PreAuthorize("hasRole('STATION_OPERATOR')")
    @Operation(summary = "CONFIRM: AWAITING_PICKUP → DELIVERED; bắt buộc If-Match, otp|qrToken, photoFileId")
    public ResponseEntity<DeliveryConfirmationView> confirm(JwtAuthenticationToken auth, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody(required = false) ConfirmDeliveryRequest request) {
        long version = IfMatchVersion.parse(ifMatch);
        if (request == null) throw new ApiException(ErrorCode.VALIDATION_ERROR, "Thiếu nội dung yêu cầu");
        var result = service.confirm(UUID.fromString(auth.getName()), id, version, request);
        var response = ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.format(result.view().orderVersion()));
        if (result.replayed()) response.header("Idempotent-Replayed", "true");
        return response.body(result.view());
    }

    @GetMapping("/orders/{id}/confirmation")
    @PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER')")
    @Operation(summary = "Bằng chứng giao hàng (thời điểm, người xác nhận, photoFileId); ảnh lấy qua /files/{id}/download-link")
    public ResponseEntity<DeliveryConfirmationView> confirmation(JwtAuthenticationToken auth, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(queries.find(UUID.fromString(auth.getName()), role(auth), id));
    }

    private static RoleName role(JwtAuthenticationToken auth) {
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> RoleName.valueOf(a.substring(5)))
                .findFirst().orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
    }
}