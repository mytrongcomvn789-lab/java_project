package com.smartdrone.application.admin;

import java.time.Clock;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.smartdrone.domain.entity.AuditLog;
import com.smartdrone.infrastructure.persistence.AuditLogRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

// Ghi nhật ký thao tác quản trị. Chạy cùng transaction với thao tác gọi nó:
// thao tác thất bại (rollback) thì bản ghi audit cũng không được lưu.
@Service
public class AuditService {
    private final AuditLogRepository logs;
    private final UserRepository users;
    private final Clock clock;

    public AuditService(AuditLogRepository logs, UserRepository users, Clock clock) {
        this.logs = logs;
        this.users = users;
        this.clock = clock;
    }

    /** detail không được chứa mật khẩu/token. */
    public void record(UUID actorId, String action, String targetType, String targetId, String detail) {
        var actor = users.findWithRoleById(actorId).orElse(null);
        logs.save(new AuditLog(actorId,
                actor == null ? null : actor.getEmail(),
                actor == null ? null : actor.getRole().getName().name(),
                action, targetType, targetId, detail, clock.instant()));
    }
}
