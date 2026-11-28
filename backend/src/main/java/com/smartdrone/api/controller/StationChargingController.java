package com.smartdrone.api.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.charging.StationChargingService;
import com.smartdrone.application.charging.dto.MaintenanceInput;
import com.smartdrone.application.charging.dto.StationChargersView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/stations/{stationId}/chargers")
@Tag(name = "Station chargers", description = "Thiết bị sạc và drone của trạm (chỉ nhân viên trạm được phân công)")
@SecurityRequirement(name = "bearerAuth")
public class StationChargingController {
    private final StationChargingService service;

    public StationChargingController(StationChargingService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasRole('STATION_OPERATOR')")
    @Operation(summary = "Chi tiết thiết bị sạc và drone của trạm (số liệu sạc là giả lập)")
    public ResponseEntity<StationChargersView> detail(JwtAuthenticationToken auth, @PathVariable UUID stationId) {
        return ok(service.detail(actor(auth), stationId));
    }

    @PatchMapping("/{chargerId}/maintenance")
    @PreAuthorize("hasRole('STATION_OPERATOR')")
    @Operation(summary = "Bật/tắt bảo trì thiết bị sạc (không áp dụng khi còn drone cắm)")
    public ResponseEntity<StationChargersView> maintenance(JwtAuthenticationToken auth,
            @PathVariable UUID stationId, @PathVariable UUID chargerId, @Valid @RequestBody MaintenanceInput input) {
        return ok(service.setMaintenance(actor(auth), stationId, chargerId, input.maintenance()));
    }

    @PostMapping("/{chargerId}/release")
    @PreAuthorize("hasRole('STATION_OPERATOR')")
    @Operation(summary = "Cho drone đã sạc đầy rời thiết bị sạc")
    public ResponseEntity<StationChargersView> release(JwtAuthenticationToken auth, @PathVariable UUID stationId,
            @PathVariable UUID chargerId) {
        return ok(service.release(actor(auth), stationId, chargerId));
    }

    private static UUID actor(JwtAuthenticationToken auth) {
        return UUID.fromString(auth.getName());
    }

    private static ResponseEntity<StationChargersView> ok(StationChargersView body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
