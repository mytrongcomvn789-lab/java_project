package com.smartdrone.application.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.smartdrone.domain.entity.AuditLog;

// DTO cho GET /v1/audit-events (task 2.4).
public final class AuditDtos {
    private AuditDtos() { }

    /** Bộ lọc tra cứu; trường null = không lọc. Khoảng thời gian [from, to). */
    public record AuditQuery(int page, int pageSize, String action, UUID actorId,
                             String targetType, String targetId, Instant from, Instant to) { }

    public record AuditEventView(UUID id, String action, UUID actorId, String actorEmail, String actorRole,
                                 String targetType, String targetId, String detail,
                                 String ipAddress, String traceId, Instant occurredAt) {
        public static AuditEventView from(AuditLog l) {
            return new AuditEventView(l.getId(), l.getAction(), l.getActorId(), l.getActorEmail(),
                    l.getActorRole(), l.getTargetType(), l.getTargetId(), l.getDetail(),
                    l.getIpAddress(), l.getTraceId(), l.getOccurredAt());
        }
    }

    public record AuditEventPage(List<AuditEventView> items, int page, int pageSize, long total) { }
}
