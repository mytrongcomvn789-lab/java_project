package com.smartdrone.api.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.dispatch.DroneDispatchService;
import com.smartdrone.application.dispatch.dto.DispatchBoardView;
import com.smartdrone.application.dispatch.dto.DispatchView;
import com.smartdrone.domain.enums.RoleName;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/v1")
@Tag(name = "Drone dispatch", description = "Điều phối drone tự động (ước tính theo công thức)")
@SecurityRequirement(name = "bearerAuth")
public class DroneDispatchController {
    private final DroneDispatchService service;

    public DroneDispatchController(DroneDispatchService service) {
        this.service = service;
    }

    @GetMapping("/orders/{id}/dispatch")
    @PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER')")
    @Operation(summary = "Kế hoạch điều phối drone của đơn: thời gian chờ, lý do chờ, giờ khởi hành và đến dự kiến. Khách hàng không thấy chi tiết vận hành")
    public ResponseEntity<DispatchView> view(JwtAuthenticationToken auth, @PathVariable UUID id) {
        return noStore(service.view(UUID.fromString(auth.getName()), role(auth), id));
    }

    @GetMapping("/dispatch/board")
    @PreAuthorize("hasAnyRole('DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER')")
    @Operation(summary = "Bảng điều phối: đội drone và các đơn đang cần hoặc đã có drone (trong phạm vi trạm được phân công)")
    public ResponseEntity<DispatchBoardView> board(JwtAuthenticationToken auth) {
        return noStore(service.board(UUID.fromString(auth.getName()), role(auth)));
    }

    @PostMapping("/orders/{id}/dispatch-plan")
    @PreAuthorize("hasRole('STATION_OPERATOR')")
    @Operation(summary = "Phân công hoặc tính lại drone cho đơn đã nhận kiện tại trạm gửi")
    public ResponseEntity<DispatchView> assign(JwtAuthenticationToken auth, @PathVariable UUID id) {
        return noStore(service.assignManually(UUID.fromString(auth.getName()), id));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static RoleName role(JwtAuthenticationToken auth) {
        for (var role : List.of(RoleName.CUSTOMER, RoleName.STATION_OPERATOR, RoleName.DISPATCHER,
                RoleName.LOGISTICS_MANAGER)) {
            if (auth.getAuthorities().stream().anyMatch(a -> ("ROLE_" + role.name()).equals(a.getAuthority()))) {
                return role;
            }
        }
        throw new AccessDeniedException("Không có quyền xem điều phối drone");
    }
}
