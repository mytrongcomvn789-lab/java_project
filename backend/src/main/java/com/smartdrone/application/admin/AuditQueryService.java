package com.smartdrone.application.admin;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.admin.dto.AuditDtos.AuditEventPage;
import com.smartdrone.application.admin.dto.AuditDtos.AuditEventView;
import com.smartdrone.application.admin.dto.AuditDtos.AuditQuery;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.entity.AuditLog;
import com.smartdrone.infrastructure.persistence.AuditLogRepository;

import jakarta.persistence.criteria.Predicate;

// Task 2.4: admin tra cứu audit log (GET /v1/audit-events), mới nhất trước.
@Service
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AuditQueryService {
    private final AuditLogRepository logs;

    public AuditQueryService(AuditLogRepository logs) {
        this.logs = logs;
    }

    @Transactional(readOnly = true)
    public AuditEventPage search(AuditQuery query) {
        if (query.page() < 1 || query.pageSize() < 1 || query.pageSize() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "page >= 1; pageSize từ 1 đến 100");
        }
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "from phải trước to");
        }
        var result = logs.findAll(spec(query), PageRequest.of(query.page() - 1, query.pageSize(),
                Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by("id"))));
        return new AuditEventPage(result.map(AuditEventView::from).getContent(),
                query.page(), query.pageSize(), result.getTotalElements());
    }

    private static Specification<AuditLog> spec(AuditQuery q) {
        return (root, cq, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (q.action() != null) where.add(cb.equal(root.get("action"), q.action()));
            if (q.actorId() != null) where.add(cb.equal(root.get("actorId"), q.actorId()));
            if (q.targetType() != null) where.add(cb.equal(root.get("targetType"), q.targetType()));
            if (q.targetId() != null) where.add(cb.equal(root.get("targetId"), q.targetId()));
            if (q.from() != null) where.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), q.from()));
            if (q.to() != null) where.add(cb.lessThan(root.get("occurredAt"), q.to()));
            return cb.and(where.toArray(Predicate[]::new));
        };
    }
}
