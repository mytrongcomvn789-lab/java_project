package com.smartdrone.api.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.notifications.CustomerNotificationService;
import com.smartdrone.application.notifications.dto.NotificationPage;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/v1/notifications")
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Notifications", description = "4.9 Hộp thông báo trạng thái đơn của khách hàng")
@SecurityRequirement(name = "bearerAuth")
public class NotificationController {
    private final CustomerNotificationService service;

    public NotificationController(CustomerNotificationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Hộp thông báo của tôi (mới nhất trước, kèm số chưa đọc); dùng để tải bù khi kết nối lại")
    public ResponseEntity<NotificationPage> list(JwtAuthenticationToken auth,
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(userId(auth), unreadOnly, page, pageSize));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Đánh dấu một thông báo đã đọc (idempotent)")
    public ResponseEntity<Void> markRead(JwtAuthenticationToken auth, @PathVariable UUID id) {
        service.markRead(userId(auth), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "Đánh dấu tất cả thông báo của tôi đã đọc")
    public ResponseEntity<Void> markAllRead(JwtAuthenticationToken auth) {
        service.markAllRead(userId(auth));
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(JwtAuthenticationToken auth) {
        return UUID.fromString(auth.getName());
    }
}