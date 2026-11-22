package com.smartdrone.infrastructure.persistence.seed;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.admin.PermissionCatalog;
import com.smartdrone.application.admin.SettingDefinitions;
import com.smartdrone.domain.entity.Permission;
import com.smartdrone.domain.entity.Role;
import com.smartdrone.domain.entity.SystemSetting;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.PermissionRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.SystemSettingRepository;

// Chạy sau DataSeeder: nạp danh mục quyền, gán quyền mặc định cho vai trò, nạp cấu hình mặc định.
// An toàn khi chạy nhiều lần: không ghi đè thay đổi của quản trị viên.
@Component
@Order(2)
public class AccessSettingsSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(AccessSettingsSeeder.class);

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final SystemSettingRepository settings;
    private final Clock clock;

    public AccessSettingsSeeder(RoleRepository roles, PermissionRepository permissions,
            SystemSettingRepository settings, Clock clock) {
        this.roles = roles;
        this.permissions = permissions;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        for (PermissionCatalog.Def d : PermissionCatalog.ALL) {
            if (!permissions.existsById(d.code())) {
                permissions.save(new Permission(d.code(), d.group(), d.label()));
            }
        }
        List<Permission> all = permissions.findAll();
        List<Role> withPerms = roles.findAllWithPermissions();

        // SYSTEM_ADMIN luôn có đủ quyền (kể cả quyền mới thêm ở phiên bản sau).
        for (Role role : withPerms) {
            if (role.getName() == RoleName.SYSTEM_ADMIN) {
                role.getPermissions().addAll(all);
            }
        }
        // Quyền mặc định của các vai trò khác chỉ nạp lần đầu (khi chưa vai trò nào có quyền),
        // để không ghi đè thay đổi của quản trị viên.
        boolean firstRun = withPerms.stream().filter(r -> r.getName() != RoleName.SYSTEM_ADMIN)
                .allMatch(r -> r.getPermissions().isEmpty());
        if (firstRun) {
            for (Role role : withPerms) {
                Set<String> defaults = PermissionCatalog.DEFAULTS.get(role.getName());
                if (defaults != null) {
                    role.getPermissions().addAll(all.stream()
                            .filter(p -> defaults.contains(p.getCode())).collect(Collectors.toSet()));
                }
            }
            log.info("Đã nạp quyền mặc định cho các vai trò");
        }
        roles.saveAll(withPerms);

        for (SettingDefinitions.Def d : SettingDefinitions.ALL) {
            if (!settings.existsById(d.key())) {
                settings.save(new SystemSetting(d.key(), d.defaultValue(), d.type(), d.description(),
                        null, clock.instant()));
            }
        }
    }
}
