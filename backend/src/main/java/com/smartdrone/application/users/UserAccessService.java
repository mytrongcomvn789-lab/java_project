package com.smartdrone.application.users;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.users.dto.UpdateUserAccessRequest;
import com.smartdrone.application.users.dto.UserAccessPage;
import com.smartdrone.application.users.dto.UserAccessView;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.RefreshTokenRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

@Service
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class UserAccessService {
    private static final Logger log = LoggerFactory.getLogger(UserAccessService.class);
    private final UserRepository users;
    private final RoleRepository roles;
    private final RefreshTokenRepository refreshTokens;
    private final Clock clock;

    public UserAccessService(UserRepository users, RoleRepository roles,
            RefreshTokenRepository refreshTokens, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.refreshTokens = refreshTokens;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public UserAccessPage list(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "page >= 1; pageSize từ 1 đến 100");
        }
        var result = users.findAll(PageRequest.of(page - 1, pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        return new UserAccessPage(result.map(UserAccessView::from).getContent(),
                page, pageSize, result.getTotalElements());
    }

    @Transactional
    public UserAccessView update(UUID actorId, UUID userId, UpdateUserAccessRequest request) {
        if (request.role() == null && request.enabled() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Cần gửi role hoặc enabled");
        }
        // Giữ khóa chung trước khi đọc quyền hiện tại. Hai admin thay đổi quyền nhau đồng thời
        // không thể cùng vượt qua kiểm tra bằng dữ liệu cũ.
        roles.findForAccessUpdate(RoleName.SYSTEM_ADMIN)
                .orElseThrow(() -> new IllegalStateException("Chưa seed vai trò SYSTEM_ADMIN"));
        User actor = users.findWithRoleById(actorId)
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
        if (!actor.isEnabled() || actor.getRole().getName() != RoleName.SYSTEM_ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        User target = users.findWithRoleById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        RoleName oldRole = target.getRole().getName();
        boolean oldEnabled = target.isEnabled();
        RoleName newRole = request.role() == null ? oldRole : request.role();
        boolean newEnabled = request.enabled() == null ? oldEnabled : request.enabled();
        if (oldRole == newRole && oldEnabled == newEnabled) {
            return UserAccessView.from(target);
        }
        if (actorId.equals(userId)) {
            throw new ApiException(ErrorCode.SELF_ACCESS_CHANGE);
        }
        target.setRole(roles.findByName(newRole)
                .orElseThrow(() -> new IllegalStateException("Chưa seed vai trò " + newRole)));
        target.setEnabled(newEnabled);
        target.invalidateAccessTokens();
        users.saveAndFlush(target);
        UserAccessView response = UserAccessView.from(target);
        // Đã flush thay đổi trước bulk update (repository có clearAutomatically).
        int revokedSessions = refreshTokens.revokeAllByUserId(userId, clock.instant());
        log.info("USER_ACCESS_CHANGED actorId={} targetId={} role={}->{} enabled={}->{} revokedSessions={}",
                actorId, userId, oldRole, newRole, oldEnabled, newEnabled, revokedSessions);
        return response;
    }
}
