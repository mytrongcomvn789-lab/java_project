package com.smartdrone.api.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.admin.AdminAccountService;
import com.smartdrone.application.admin.AdminRoleService;
import com.smartdrone.application.admin.AdminSettingsService;
import com.smartdrone.application.admin.dto.AccountDtos.AccountPage;
import com.smartdrone.application.admin.dto.AccountDtos.AccountStatusRequest;
import com.smartdrone.application.admin.dto.AccountDtos.AccountView;
import com.smartdrone.application.admin.dto.AccountDtos.CreateAccountRequest;
import com.smartdrone.application.admin.dto.AccountDtos.UpdateAccountRequest;
import com.smartdrone.application.admin.dto.RoleDtos.RoleCatalog;
import com.smartdrone.application.admin.dto.RoleDtos.RoleView;
import com.smartdrone.application.admin.dto.RoleDtos.UpdateRolePermissionsRequest;
import com.smartdrone.application.admin.dto.SettingDtos.SettingsResponse;
import com.smartdrone.application.admin.dto.SettingDtos.UpdateSettingsRequest;
import com.smartdrone.domain.enums.RoleName;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

// FR-02: quản trị hệ thống. Quyền kiểm tra ở 2 lớp: RbacPolicy (URL) và @PreAuthorize ở service.
@RestController
@RequestMapping("/v1/admin")
@Tag(name = "Admin", description = "FR-02: tài khoản, vai trò & quyền, cấu hình hệ thống (SYSTEM_ADMIN)")
@SecurityRequirement(name = "bearerAuth")
public class AdminController {
    private final AdminAccountService accounts;
    private final AdminRoleService roles;
    private final AdminSettingsService settings;

    public AdminController(AdminAccountService accounts, AdminRoleService roles, AdminSettingsService settings) {
        this.accounts = accounts;
        this.roles = roles;
        this.settings = settings;
    }

    // ---- Tài khoản ----
    @Operation(summary = "Danh sách tài khoản: tìm kiếm tên/email/SĐT, lọc vai trò và trạng thái")
    @GetMapping("/accounts")
    public ResponseEntity<AccountPage> listAccounts(@RequestParam(required = false) String search,
            @RequestParam(required = false) RoleName role, @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize) {
        return noStore(accounts.list(search, role, enabled, page, pageSize));
    }

    @Operation(summary = "Tạo tài khoản (admin chọn vai trò, đặt mật khẩu ban đầu)")
    @PostMapping("/accounts")
    public ResponseEntity<AccountView> createAccount(@Valid @RequestBody CreateAccountRequest request,
            JwtAuthenticationToken auth) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(accounts.create(actor(auth), request));
    }

    @Operation(summary = "Cập nhật họ tên, số điện thoại, vai trò")
    @PutMapping("/accounts/{id}")
    public ResponseEntity<AccountView> updateAccount(@PathVariable UUID id,
            @Valid @RequestBody UpdateAccountRequest request, JwtAuthenticationToken auth) {
        return noStore(accounts.update(actor(auth), id, request));
    }

    @Operation(summary = "Khóa / mở khóa tài khoản")
    @PatchMapping("/accounts/{id}/status")
    public ResponseEntity<AccountView> setStatus(@PathVariable UUID id,
            @Valid @RequestBody AccountStatusRequest request, JwtAuthenticationToken auth) {
        return noStore(accounts.setEnabled(actor(auth), id, request.enabled()));
    }

    // ---- Vai trò & quyền ----
    @Operation(summary = "Danh mục quyền và các vai trò kèm quyền")
    @GetMapping("/roles")
    public ResponseEntity<RoleCatalog> listRoles() {
        return noStore(roles.catalog());
    }

    @Operation(summary = "Thay toàn bộ quyền của một vai trò (SYSTEM_ADMIN bị khóa)")
    @PutMapping("/roles/{role}/permissions")
    public ResponseEntity<RoleView> updateRolePermissions(@PathVariable RoleName role,
            @Valid @RequestBody UpdateRolePermissionsRequest request, JwtAuthenticationToken auth) {
        return noStore(roles.updatePermissions(actor(auth), role, request.permissions()));
    }

    // ---- Cấu hình hệ thống ----
    @Operation(summary = "Lấy toàn bộ cấu hình hệ thống")
    @GetMapping("/settings")
    public ResponseEntity<SettingsResponse> getSettings() {
        return noStore(settings.list());
    }

    @Operation(summary = "Cập nhật cấu hình (validate kiểu và khoảng giá trị)")
    @PutMapping("/settings")
    public ResponseEntity<SettingsResponse> updateSettings(@Valid @RequestBody UpdateSettingsRequest request,
            JwtAuthenticationToken auth) {
        return noStore(settings.update(actor(auth), request.values()));
    }

    private static UUID actor(JwtAuthenticationToken auth) {
        return UUID.fromString(auth.getName());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
