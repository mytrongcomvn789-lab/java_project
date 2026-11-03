package com.smartdrone.api.controller;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.api.security.RbacPolicy;
import com.smartdrone.application.users.UserAccessService;
import com.smartdrone.application.users.dto.UpdateUserAccessRequest;
import com.smartdrone.application.users.dto.UserAccessPage;
import com.smartdrone.application.users.dto.UserAccessView;
import com.smartdrone.domain.enums.RoleName;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/v1")
@Tag(name = "Access", description = "APDDMP-18: phân quyền cho 5 actor")
@SecurityRequirement(name = "bearerAuth")
public class AccessController {
    private final UserAccessService service;

    public AccessController(UserAccessService service) {
        this.service = service;
    }

    @GetMapping("/me/permissions")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Vai trò và chính sách endpoint của tài khoản hiện tại",
            description = "Danh sách quyền không khẳng định endpoint nghiệp vụ đã triển khai; vẫn cần kiểm tra chủ đơn/trạm.")
    public ResponseEntity<MyPermissions> myPermissions(JwtAuthenticationToken authentication) {
        RoleName role = RoleName.valueOf(authentication.getToken().getClaimAsString("role"));
        return noStore(new MyPermissions(UUID.fromString(authentication.getName()), role, endpoints(role)));
    }

    @GetMapping("/roles")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "Admin xem 5 vai trò và ma trận quyền")
    public ResponseEntity<List<RoleAccess>> roles() {
        return noStore(Arrays.stream(RoleName.values())
                .map(role -> new RoleAccess(role, label(role), endpoints(role))).toList());
    }

    @GetMapping("/users")
    @Operation(summary = "Admin xem danh sách tài khoản (page từ 1, pageSize tối đa 100)")
    public ResponseEntity<UserAccessPage> users(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return noStore(service.list(page, pageSize));
    }

    @PatchMapping("/users/{id}/access")
    @Operation(summary = "Admin cấp/đổi vai trò hoặc khóa/mở khóa tài khoản khác",
            description = "Thu hồi refresh token khi quyền thay đổi. JWT cũ sai role hoặc tài khoản bị khóa bị từ chối ngay ở request sau.")
    public ResponseEntity<UserAccessView> update(@PathVariable UUID id,
            @RequestBody UpdateUserAccessRequest request, JwtAuthenticationToken authentication) {
        return noStore(service.update(UUID.fromString(authentication.getName()), id, request));
    }

    private static List<EndpointAccess> endpoints(RoleName role) {
        return RbacPolicy.rules().stream().filter(rule -> rule.roles().contains(role))
                .map(rule -> new EndpointAccess(rule.method().name(), rule.path())).toList();
    }

    private static String label(RoleName role) {
        return switch (role) {
            case CUSTOMER -> "Khách hàng";
            case DISPATCHER -> "Điều phối viên";
            case STATION_OPERATOR -> "Nhân viên trạm";
            case LOGISTICS_MANAGER -> "Quản lý hậu cần";
            case SYSTEM_ADMIN -> "Quản trị viên";
        };
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }

    public record EndpointAccess(String method, String path) { }
    public record MyPermissions(UUID userId, RoleName role, List<EndpointAccess> allowedEndpoints) { }
    public record RoleAccess(RoleName role, String displayName, List<EndpointAccess> allowedEndpoints) { }
}
