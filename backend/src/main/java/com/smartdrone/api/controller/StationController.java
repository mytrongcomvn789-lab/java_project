package com.smartdrone.api.controller;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.smartdrone.application.stations.StationService;
import com.smartdrone.application.stations.dto.StationInput;
import com.smartdrone.application.stations.dto.StationStatusInput;
import com.smartdrone.application.stations.dto.StationView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/stations")
@Tag(name = "Stations", description = "Quản lý thông tin trạm hạ cánh")
@SecurityRequirement(name = "bearerAuth")
public class StationController {
    private final StationService service;

    public StationController(StationService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER','SYSTEM_ADMIN')")
    @Operation(summary = "Danh sách trạm")
    public ResponseEntity<List<StationView>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list());
    }

    @PostMapping
    @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
    @Operation(summary = "Tạo trạm")
    public ResponseEntity<StationView> create(@Valid @RequestBody StationInput input) {
        StationView result = service.create(input);
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(result.id()).toUri();
        return ResponseEntity.created(location).cacheControl(CacheControl.noStore()).body(result);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
    @Operation(summary = "Cập nhật thông tin trạm")
    public ResponseEntity<StationView> update(@PathVariable UUID id, @Valid @RequestBody StationInput input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.update(id, input));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('LOGISTICS_MANAGER','STATION_OPERATOR')")
    @Operation(summary = "Đổi trạng thái trạm")
    public ResponseEntity<StationView> changeStatus(@PathVariable UUID id,
            @Valid @RequestBody StationStatusInput input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.changeStatus(id, input));
    }
}
