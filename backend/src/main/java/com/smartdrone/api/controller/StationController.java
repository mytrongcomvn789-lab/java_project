
package com.smartdrone.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.stations.StationService;
import com.smartdrone.application.stations.dto.StationRequest;
import com.smartdrone.application.stations.dto.StationResponse;
import com.smartdrone.application.stations.dto.StationStatusRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/stations")
public class StationController {

    private final StationService stationService;

    public StationController(StationService stationService) {
        this.stationService = stationService;
    }

    // GET: Lấy danh sách trạm
    @GetMapping
    public ResponseEntity<List<StationResponse>> getAll() {
        return ResponseEntity.ok(stationService.getAll());
    }

    // POST: Tạo trạm mới
    @PostMapping
    public ResponseEntity<StationResponse> create(
            @Valid @RequestBody StationRequest request) {

        StationResponse response = stationService.create(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // PUT: Cập nhật thông tin trạm
    @PutMapping("/{id}")
    public ResponseEntity<StationResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody StationRequest request) {

        return ResponseEntity.ok(
                stationService.update(id, request)
        );
    }

    // PATCH: Cập nhật trạng thái trạm
    @PatchMapping("/{id}/status")
    public ResponseEntity<StationResponse> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody StationStatusRequest request) {

        return ResponseEntity.ok(
                stationService.updateStatus(id, request.status())
        );
    }
}
