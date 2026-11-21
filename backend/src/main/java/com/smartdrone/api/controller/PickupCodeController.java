package com.smartdrone.api.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.pickup.PickupChallengeService;
import com.smartdrone.application.pickup.dto.PickupCodeView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/v1")
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Pickup code", description = "4.7 Mã OTP/QR nhận hàng cho khách")
@SecurityRequirement(name = "bearerAuth")
public class PickupCodeController {
    private final PickupChallengeService service;

    public PickupCodeController(PickupChallengeService service) {
        this.service = service;
    }

    @GetMapping("/orders/{id}/pickup-code")
    @Operation(summary = "Mã OTP + QR nhận hàng của đơn AWAITING_PICKUP; dùng lại mã còn hạn, hết hạn thì cấp mới")
    public ResponseEntity<PickupCodeView> pickupCode(JwtAuthenticationToken auth, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.currentCode(UUID.fromString(auth.getName()), id));
    }
}