package com.smartdrone.application.admin;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.admin.dto.AccountDtos.AccountPage;
import com.smartdrone.application.admin.dto.AccountDtos.AccountView;
import com.smartdrone.application.admin.dto.AccountDtos.CreateAccountRequest;
import com.smartdrone.application.admin.dto.AccountDtos.UpdateAccountRequest;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.entity.Role;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.RefreshTokenRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import jakarta.persistence.criteria.Predicate;

// FR-02: quản lý tài khoản (chỉ SYSTEM_ADMIN). Không có thao tác xóa tài khoản.
@Service
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminAccountService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Clock clock;

    public AdminAccountService(UserRepository users, RoleRepository roles, RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder, AuditService audit, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AccountPage list(String search, RoleName role, Boolean enabled, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "page >= 1; pageSize từ 1 đến 100");
        }
        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> parts = new ArrayList<>();
            if (search != null && !search.isBlank()) {
                String like = "%" + escapeLike(search.trim().toLowerCase(Locale.ROOT)) + "%";
                parts.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), like, '\\'),
                        cb.like(cb.lower(root.get("email")), like, '\\'),
                        cb.like(cb.coalesce(root.<String>get("phone"), ""), like, '\\')));
            }
            if (role != null) {
                parts.add(cb.equal(root.get("role").get("name"), role));
            }
            if (enabled != null) {
                parts.add(cb.equal(root.get("enabled"), enabled));
            }
            return cb.and(parts.toArray(Predicate[]::new));
        };
        var result = users.findAll(spec, PageRequest.of(page - 1, pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))));
        return new AccountPage(result.map(AccountView::from).getContent(), page, pageSize, result.getTotalElements());
    }

    @Transactional
    public AccountView create(UUID actorId, CreateAccountRequest req) {
        if (req.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Mật khẩu tối đa 72 byte");
        }
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        Role role = roles.findByName(req.role())
                .orElseThrow(() -> new IllegalStateException("Chưa seed vai trò " + req.role()));
        User user = new User(email, passwordEncoder.encode(req.password()), req.fullName().trim(), role);
        user.setPhone(req.phone().trim());
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        audit.record(actorId, "ACCOUNT_CREATED", "USER", user.getId().toString(),
                "email=" + email + ", role=" + req.role());
        return AccountView.from(user);
    }

    @Transactional
    public AccountView update(UUID actorId, UUID userId, UpdateAccountRequest req) {
        lockAdmins();
        User target = users.findWithRoleById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        RoleName oldRole = target.getRole().getName();
        boolean roleChanged = oldRole != req.role();
        if (roleChanged) {
            if (actorId.equals(userId)) {
                throw new ApiException(ErrorCode.SELF_ACCESS_CHANGE);
            }
            guardLastAdmin(target, oldRole);
            target.setRole(roles.findByName(req.role())
                    .orElseThrow(() -> new IllegalStateException("Chưa seed vai trò " + req.role())));
        }
        target.setFullName(req.fullName().trim());
        target.setPhone(req.phone().trim());
        if (roleChanged) {
            // Đổi vai trò: vô hiệu token cũ và đăng xuất mọi thiết bị.
            target.invalidateAccessTokens();
        }
        users.saveAndFlush(target);
        if (roleChanged) {
            refreshTokens.revokeAllByUserId(userId, clock.instant());
        }
        audit.record(actorId, "ACCOUNT_UPDATED", "USER", userId.toString(),
                roleChanged ? "role " + oldRole + " -> " + req.role() : "cập nhật họ tên/số điện thoại");
        return AccountView.from(target);
    }

    @Transactional
    public AccountView setEnabled(UUID actorId, UUID userId, boolean enabled) {
        lockAdmins();
        User target = users.findWithRoleById(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (target.isEnabled() == enabled) {
            return AccountView.from(target);
        }
        if (actorId.equals(userId)) {
            throw new ApiException(ErrorCode.SELF_ACCESS_CHANGE);
        }
        if (!enabled) {
            guardLastAdmin(target, target.getRole().getName());
        }
        target.setEnabled(enabled);
        target.invalidateAccessTokens();
        users.saveAndFlush(target);
        refreshTokens.revokeAllByUserId(userId, clock.instant());
        audit.record(actorId, enabled ? "ACCOUNT_UNLOCKED" : "ACCOUNT_LOCKED", "USER", userId.toString(),
                "email=" + target.getEmail());
        return AccountView.from(target);
    }

    // Giữ khóa chung với UserAccessService để hai admin không cùng vượt kiểm tra "admin cuối cùng".
    private void lockAdmins() {
        roles.findForAccessUpdate(RoleName.SYSTEM_ADMIN)
                .orElseThrow(() -> new IllegalStateException("Chưa seed vai trò SYSTEM_ADMIN"));
    }

    // Chặn khóa/hạ quyền quản trị viên đang hoạt động cuối cùng.
    private void guardLastAdmin(User target, RoleName currentRole) {
        if (currentRole == RoleName.SYSTEM_ADMIN && target.isEnabled()
                && users.countActiveByRole(RoleName.SYSTEM_ADMIN) <= 1) {
            throw new ApiException(ErrorCode.LAST_ADMIN_PROTECTED);
        }
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
