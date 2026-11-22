package com.smartdrone.api.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.statistics.DateBasis;
import com.smartdrone.application.statistics.StatisticsService;
import com.smartdrone.application.statistics.dto.DailyOrderStats;
import com.smartdrone.application.statistics.dto.OrderStatsSummary;
import com.smartdrone.application.statistics.dto.StationLoadStat;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 5.1: API thống kê cho dashboard (5.2) và báo cáo (5.3). Chỉ LOGISTICS_MANAGER, kiểm tra ở hai lớp:
 * RbacPolicy (URL) và @PreAuthorize (controller + service). Công thức và định nghĩa: docs/05-api/statistics-api.md.
 */
@RestController
@RequestMapping("/v1/statistics")
@PreAuthorize("hasRole('LOGISTICS_MANAGER')")
@Tag(name = "Statistics", description = "5.1 Thống kê đơn, tỷ lệ giao và tải trạm (chỉ quản lý hậu cần)")
@SecurityRequirement(name = "bearerAuth")
public class StatisticsController {
    private final StatisticsService service;

    public StatisticsController(StatisticsService service) { this.service = service; }

    @GetMapping("/orders/summary")
    @Operation(summary = "Số đơn theo nhóm/trạng thái, tỷ lệ giao-hoàn-hủy, số attempt và thời gian giao. "
            + "from/to: yyyy-MM-dd (gồm hai đầu, múi giờ Asia/Ho_Chi_Minh, mặc định 30 ngày, tối đa 366 ngày)")
    public ResponseEntity<OrderStatsSummary> summary(JwtAuthenticationToken auth,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) DateBasis dateBasis,
            @RequestParam(required = false) UUID stationId) {
        var period = service.period(from, to, dateBasis, stationId);
        return noStore(service.summary(actor(auth), period));
    }

    @GetMapping("/orders/daily")
    @Operation(summary = "Số đơn theo từng ngày địa phương; ngày không có đơn vẫn có dòng với mọi số bằng 0")
    public ResponseEntity<List<DailyOrderStats>> daily(JwtAuthenticationToken auth,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) DateBasis dateBasis,
            @RequestParam(required = false) UUID stationId) {
        var period = service.period(from, to, dateBasis, stationId);
        return noStore(service.daily(actor(auth), period));
    }

    @GetMapping("/stations/load")
    @Operation(summary = "Tải trạm trong một khung giờ: sức chứa tối đa, đã đặt, đang xử lý (mặc định khung giờ hiện tại)")
    public ResponseEntity<List<StationLoadStat>> stationLoad(JwtAuthenticationToken auth,
            @RequestParam(required = false) UUID stationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant at) {
        return noStore(service.stationLoad(actor(auth), stationId, at));
    }

    private static UUID actor(JwtAuthenticationToken auth) {
        return UUID.fromString(auth.getName());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
