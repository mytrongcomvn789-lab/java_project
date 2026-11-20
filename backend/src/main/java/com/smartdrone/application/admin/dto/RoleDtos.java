package com.smartdrone.application.admin.dto;

import java.util.List;

import com.smartdrone.domain.enums.RoleName;

import jakarta.validation.constraints.NotNull;

public final class RoleDtos {
    private RoleDtos() { }

    public record PermissionView(String code, String group, String label) { }

    // locked = true: không cho sửa quyền (SYSTEM_ADMIN). isDefault: vai trò hệ thống mặc định.
    public record RoleView(RoleName role, String displayName, String description, boolean isDefault,
                           boolean locked, long userCount, List<String> permissions) { }

    public record RoleCatalog(List<PermissionView> permissions, List<RoleView> roles) { }

    public record UpdateRolePermissionsRequest(
            @NotNull(message = "Thiếu danh sách quyền")
            List<String> permissions) { }
}
