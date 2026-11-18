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
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.common.IfMatchVersion;
import com.smartdrone.application.orders.OrderTrackingService;
import com.smartdrone.application.orders.dto.TrackingView;
import com.smartdrone.domain.enums.RoleName;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/v1")
@PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER')")
@Tag(name = "Order tracking", description = "4.4 Timeline và vị trí trạm của đơn; dùng cho web và mobile")
@SecurityRequirement(name = "bearerAuth")
public class OrderTrackingController {
    private final OrderTrackingService service;
    public OrderTrackingController(OrderTrackingService service) { this.service = service; }

    @GetMapping("/orders/{id}/tracking")
    @Operation(summary = "Timeline (tăng dần theo orderVersion), hai trạm kèm toạ độ, thao tác được phép; ngoài phạm vi → 404")
    public ResponseEntity<TrackingView> tracking(JwtAuthenticationToken auth, @PathVariable UUID id) {
        TrackingView view = service.get(UUID.fromString(auth.getName()), roleOf(auth), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .eTag(IfMatchVersion.format(view.version())).body(view);
    }

    private static RoleName roleOf(JwtAuthenticationToken auth) {
        return auth.getAuthorities().stream().map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_")).map(a -> RoleName.valueOf(a.substring(5)))
                .findFirst().orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
    }
}
