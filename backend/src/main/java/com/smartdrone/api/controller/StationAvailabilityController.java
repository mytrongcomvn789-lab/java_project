package com.smartdrone.api.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.scheduling.StationAvailabilityService;
import com.smartdrone.application.scheduling.StationAvailabilityView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/v1/stations")
@Tag(name = "Station availability", description = "4.2 Chỗ còn trống của trạm theo từng slot")
@SecurityRequirement(name = "bearerAuth")
public class StationAvailabilityController {

    private final StationAvailabilityService service;

    public StationAvailabilityController(StationAvailabilityService service) {
        this.service = service;
    }

    @GetMapping("/{id}/availability")
    @PreAuthorize("hasAnyRole('DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER')")
    @Operation(summary = "Chỗ còn trống từng slot trong ngày (date dạng yyyy-MM-dd, giờ Việt Nam; bỏ trống = hôm nay)")
    public ResponseEntity<StationAvailabilityView> availability(@PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id, date));
    }
}