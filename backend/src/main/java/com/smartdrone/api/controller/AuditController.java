package com.smartdrone.api.controller;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.admin.AuditQueryService;
import com.smartdrone.application.admin.dto.AuditDtos.AuditEventPage;
import com.smartdrone.application.admin.dto.AuditDtos.AuditQuery;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/v1")
@Tag(name = "Audit", description = "Task 2.4: nhật ký thao tác quan trọng (NFR-07)")
@SecurityRequirement(name = "bearerAuth")
public class AuditController {
    private final AuditQueryService service;

    public AuditController(AuditQueryService service) {
        this.service = service;
    }

    @GetMapping("/audit-events")
    @Operation(summary = "Admin tra cứu audit log (mới nhất trước; page từ 1, pageSize tối đa 100)",
            description = "Lọc tùy chọn theo action, actorId, targetType, targetId và khoảng thời gian [from, to) "
                    + "dạng ISO-8601 (vd: 2026-11-22T00:00:00Z).")
    public ResponseEntity<AuditEventPage> search(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        AuditEventPage result = service.search(
                new AuditQuery(page, pageSize, action, actorId, targetType, targetId, from, to));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }
}
