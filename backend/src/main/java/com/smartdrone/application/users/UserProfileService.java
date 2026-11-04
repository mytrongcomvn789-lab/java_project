package com.smartdrone.application.users;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.auth.dto.UserProfile;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.users.dto.ChangePasswordRequest;
import com.smartdrone.application.users.dto.UpdateProfileRequest;
import com.smartdrone.domain.entity.User;
import com.smartdrone.infrastructure.persistence.RefreshTokenRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

@Service
public class UserProfileService {
    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public UserProfileService(UserRepository users, RefreshTokenRepository refreshTokens,
                              PasswordEncoder passwordEncoder, Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    // 1. Xem hồ sơ
    @Transactional(readOnly = true)
    public UserProfile get(UUID userId) {
        return UserProfile.from(load(userId));
    }

    // 2. Sửa hồ sơ (chỉ fullName, phone; null = không đổi)
    @Transactional
    public UserProfile update(UUID userId, UpdateProfileRequest request) {
        if (request.fullName() == null && request.phone() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Cần gửi fullName hoặc phone");
        }
        User user = load(userId);
        if (request.fullName() != null) {
            String name = request.fullName().trim();
            if (name.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Họ tên không được để trống");
            }
            user.setFullName(name);
        }
        if (request.phone() != null) {
            String phone = request.phone().trim();
            user.setPhone(phone.isEmpty() ? null : phone);
        }
        return UserProfile.from(users.saveAndFlush(user));
    }

    // 3. Đổi mật khẩu
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest request) {
        User user = load(userId);

        if (tooLong(request.newPassword())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Mật khẩu tối đa 72 byte");
        }
        if (tooLong(request.currentPassword())
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CURRENT_PASSWORD);
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Mật khẩu mới phải khác mật khẩu hiện tại");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.invalidateAccessTokens();
        users.saveAndFlush(user);
        int revoked = refreshTokens.revokeAllByUserId(userId, clock.instant());
        log.info("PASSWORD_CHANGED userId={} revokedSessions={}", userId, revoked);
    }

    private User load(UUID id) {
        return users.findWithRoleById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    private static boolean tooLong(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }
}