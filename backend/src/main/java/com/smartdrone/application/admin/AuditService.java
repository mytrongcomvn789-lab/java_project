package com.smartdrone.application.admin;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.smartdrone.application.common.TraceContext;
import com.smartdrone.domain.entity.AuditLog;
import com.smartdrone.infrastructure.persistence.AuditLogRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

// Ghi nhật ký thao tác quan trọng (NFR-07). Mỗi dòng kèm IP và traceId của request (task 2.4).
//  - record(...): chạy cùng transaction với thao tác gọi nó: thao tác thất bại (rollback)
//    thì bản ghi audit cũng không được lưu, nên log không ghi việc đã không xảy ra.
//  - recordIndependently(...): transaction riêng, dùng cho lần thử thất bại (vd: đăng nhập sai)
//    mà ngay sau đó thao tác ném lỗi và rollback.
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository logs;
    private final UserRepository users;
    private final Clock clock;

    public AuditService(AuditLogRepository logs, UserRepository users, Clock clock) {
        this.logs = logs;
        this.users = users;
        this.clock = clock;
    }

    /** detail không được chứa mật khẩu/token. actorId null = chưa xác định được người làm. */
    public void record(UUID actorId, String action, String targetType, String targetId, String detail) {
        var actor = actorId == null ? null : users.findWithRoleById(actorId).orElse(null);
        logs.save(new AuditLog(actorId,
                actor == null ? null : actor.getEmail(),
                actor == null ? null : actor.getRole().getName().name(),
                action, targetType, targetId, detail, clientIp(), TraceContext.current(), clock.instant()));
    }

    /** Như record(...), người làm là tài khoản đang đăng nhập của request hiện tại. */
    public void recordForCurrentUser(String action, String targetType, String targetId, String detail) {
        record(currentUserId(), action, targetType, targetId, detail);
    }

    /** Ghi trong transaction riêng: vẫn còn dù thao tác bên ngoài rollback. Lỗi ghi log không làm hỏng request. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(UUID actorId, String action, String targetType, String targetId,
            String detail) {
        try {
            record(actorId, action, targetType, targetId, detail);
            logs.flush();
        } catch (RuntimeException e) {
            log.error("Không ghi được audit {} actor={} target={}:{}", action, actorId, targetType, targetId, e);
        }
    }

    // JWT: subject (getName) là id tài khoản. Không có đăng nhập (job nền, API key...) thì trả null.
    private static UUID currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        try {
            return UUID.fromString(auth.getName());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // IP kết nối trực tiếp. Không tin X-Forwarded-For vì client tự đặt được header này.
    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest().getRemoteAddr();
        }
        return null; // chạy ngoài request (job nền)
    }
}
