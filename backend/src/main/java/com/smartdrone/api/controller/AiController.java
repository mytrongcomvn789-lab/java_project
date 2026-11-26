package com.smartdrone.api.controller;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.ai.AiDataAccessGuard;
import com.smartdrone.application.ai.AiResult;
import com.smartdrone.application.ai.AiService;
import com.smartdrone.application.ai.AiTask;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.statistics.DateBasis;
import com.smartdrone.domain.enums.RoleName;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 7.1: controller KHUNG cho /v1/ai/** (hợp đồng nháp, chờ chốt với 7.2-7.6). Chỉ JWT người dùng (RbacPolicy +
 * @PreAuthorize); API key 5.4 không dùng được. ETA đã triển khai tại 7.2, chat tại 7.4, analytics tại 7.5; tóm tắt còn theo hợp đồng 7.1: chỉ kiểm quyền, thu thập dữ liệu
 * tối thiểu qua AiDataAccessGuard rồi gọi AiService. Chi tiết: docs/05-api/ai-api.md.
 */
@RestController
@RequestMapping("/v1/ai")
@PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER')")
@Tag(name = "AI", description = "7.1 Khung AI Service (hợp đồng nháp v1)")
@SecurityRequirement(name = "bearerAuth")
public class AiController {
    private final com.smartdrone.application.ai.analytics.OperationsAnalyticsService analytics;
    private final AiService ai;
    private final AiDataAccessGuard guard;
    private final com.smartdrone.application.ai.chat.CustomerChatService chat;

    private final com.smartdrone.application.eta.EtaService eta;

    public AiController(AiService ai, AiDataAccessGuard guard, com.smartdrone.application.ai.chat.CustomerChatService chat,
            com.smartdrone.application.eta.EtaService eta,
            com.smartdrone.application.ai.analytics.OperationsAnalyticsService analytics) {
        this.analytics = analytics;
        this.ai = ai;
        this.guard = guard;
        this.chat = chat;
        this.eta = eta;
    }

    public record ChatRequest(UUID conversationId, @NotBlank String message, UUID orderId) { }

    public record AnalyticsRequest(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                   DateBasis dateBasis, UUID stationId) { }

    @GetMapping("/orders/{id}/eta")
    @PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR')")
    @Operation(summary = "7.2: ETA bản công thức (khoảng cách ÷ vận tốc + chờ trạm), không dùng AI/ML")
    public ResponseEntity<AiResult> eta(JwtAuthenticationToken auth, @PathVariable UUID id) {
        return ok(eta.estimate(user(auth), role(auth), id));
    }

    @PostMapping("/orders/{id}/summary")
    @PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR')")
    @Operation(summary = "Tóm tắt đơn hàng (khung 7.1; logic thật ở 7.3)")
    public ResponseEntity<AiResult> summary(JwtAuthenticationToken auth, @PathVariable UUID id) {
        var blocks = guard.orderBlocks(AiTask.ORDER_SUMMARY, user(auth), role(auth), id);
        return ok(ai.run(user(auth), role(auth), AiTask.ORDER_SUMMARY, blocks));
    }

    @PostMapping("/chat")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "7.4: Chatbot khách hàng, hội thoại riêng và dữ liệu đơn theo quyền")
    public ResponseEntity<AiResult> chat(JwtAuthenticationToken auth, @Valid @RequestBody ChatRequest request) {
        return ok(chat.chat(user(auth), role(auth), request.conversationId(), request.orderId(), request.message()));
    }

    @PostMapping("/analytics")
    @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
    @Operation(summary = "7.5: Phân tích vận hành theo quyền, số liệu xác minh và fallback")
    public ResponseEntity<AiResult> analytics(JwtAuthenticationToken auth, @RequestBody AnalyticsRequest request) {
        return ok(analytics.analyze(user(auth), role(auth), request.from(), request.to(),
                request.dateBasis(), request.stationId()));
    }

    private static ResponseEntity<AiResult> ok(AiResult body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static UUID user(JwtAuthenticationToken auth) {
        return UUID.fromString(auth.getName());
    }

    private static RoleName role(JwtAuthenticationToken auth) {
        return auth.getAuthorities().stream().map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_")).map(a -> RoleName.valueOf(a.substring(5)))
                .findFirst().orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
    }
}
