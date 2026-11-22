package com.smartdrone.application.admin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.admin.dto.RoleDtos.PermissionView;
import com.smartdrone.application.admin.dto.RoleDtos.RoleCatalog;
import com.smartdrone.application.admin.dto.RoleDtos.RoleView;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.entity.Permission;
import com.smartdrone.domain.entity.Role;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.PermissionRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

// FR-02: vai trò & quyền (chỉ SYSTEM_ADMIN). Vai trò là 5 vai trò hệ thống mặc định, chưa hỗ trợ vai trò tùy chỉnh.
@Service
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminRoleService {
    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final UserRepository users;
    private final AuditService audit;

    public AdminRoleService(RoleRepository roles, PermissionRepository permissions, UserRepository users,
            AuditService audit) {
        this.roles = roles;
        this.permissions = permissions;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public RoleCatalog catalog() {
        List<PermissionView> perms = PermissionCatalog.ALL.stream()
                .map(d -> new PermissionView(d.code(), d.group(), d.label())).toList();
        List<RoleView> views = new ArrayList<>();
        for (Role role : roles.findAllWithPermissions()) {
            views.add(toView(role));
        }
        views.sort(Comparator.comparing(v -> v.role().ordinal()));
        return new RoleCatalog(perms, views);
    }

    @Transactional
    public RoleView updatePermissions(UUID actorId, RoleName name, List<String> codes) {
        // SYSTEM_ADMIN luôn có toàn bộ quyền, không cho sửa để không thể tự khóa quyền quản trị.
        if (name == RoleName.SYSTEM_ADMIN) {
            throw new ApiException(ErrorCode.ROLE_LOCKED);
        }
        Set<String> wanted = new HashSet<>(codes);
        List<Permission> found = permissions.findAllById(wanted);
        if (found.size() != wanted.size()) {
            Set<String> known = found.stream().map(Permission::getCode).collect(Collectors.toSet());
            wanted.removeAll(known);
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Quyền không tồn tại: " + String.join(", ", wanted));
        }
        Role role = roles.findAllWithPermissions().stream().filter(r -> r.getName() == name).findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        Set<String> before = role.getPermissions().stream().map(Permission::getCode).collect(Collectors.toSet());
        Set<String> after = found.stream().map(Permission::getCode).collect(Collectors.toSet());
        if (before.equals(after)) {
            return toView(role);
        }
        role.getPermissions().clear();
        role.getPermissions().addAll(found);
        roles.saveAndFlush(role);

        Set<String> added = new HashSet<>(after);
        added.removeAll(before);
        Set<String> removed = new HashSet<>(before);
        removed.removeAll(after);
        audit.record(actorId, "ROLE_PERMISSIONS_UPDATED", "ROLE", name.name(),
                "thêm=" + sorted(added) + ", bỏ=" + sorted(removed));
        return toView(role);
    }

    private RoleView toView(Role role) {
        RoleName n = role.getName();
        List<String> codes = role.getPermissions().stream().map(Permission::getCode).sorted().toList();
        return new RoleView(n, PermissionCatalog.displayName(n), PermissionCatalog.description(n), true,
                n == RoleName.SYSTEM_ADMIN, users.countByRoleName(n), codes);
    }

    private static List<String> sorted(Set<String> s) {
        return s.stream().sorted().toList();
    }
}
