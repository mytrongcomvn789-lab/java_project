package com.smartdrone.application.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.auth.dto.LoginRequest;
import com.smartdrone.application.auth.dto.RefreshRequest;
import com.smartdrone.application.auth.dto.RegisterRequest;
import com.smartdrone.application.auth.dto.TokenResponse;
import com.smartdrone.application.auth.dto.UserProfile;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.entity.RefreshToken;
import com.smartdrone.domain.entity.Role;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.RefreshTokenRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

// Use case xác thực: đăng ký, đăng nhập, làm mới token, đăng xuất (FR-01).
@Service
public class AuthService {

    // BCrypt chỉ đọc tối đa 72 byte đầu của mật khẩu.
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    // Hash giả để đăng nhập email không tồn tại vẫn tốn thời gian như email có thật
    // (tránh dò email nào đã đăng ký bằng cách đo thời gian phản hồi).
    private final String dummyHash;

    public AuthService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       AccessTokenIssuer accessTokenIssuer,
                       JwtProperties jwtProperties,
                       Clock clock) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-only");
    }

    // Ghi chú task 2.5: Register LUÔN gán role CUSTOMER, không nhận role từ client.
    // Muốn có DISPATCHER / STATION_OPERATOR / LOGISTICS_MANAGER thì quản trị viên đổi role sau (task 2.6/2.7).
    @Transactional
    public UserProfile register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (passwordTooLongForBcrypt(request.password())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Mật khẩu tối đa 72 byte");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }

        Role customerRole = roleRepository.findByName(RoleName.CUSTOMER)
                .orElseThrow(() -> new IllegalStateException(
                        "Chưa có vai trò CUSTOMER trong CSDL (DataSeeder chưa chạy?)"));

        User user = new User(email, passwordEncoder.encode(request.password()),
                request.fullName().trim(), customerRole);
        user.setPhone(blankToNull(request.phone()));

        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Hai request đăng ký cùng email chạy song song: unique index chặn request thứ hai.
            throw new ApiException(ErrorCode.EMAIL_ALREADY_EXISTS);
        }
        return toProfile(user);
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        String email = normalizeEmail(request.email());
        User user = userRepository.findByEmail(email).orElse(null);

        boolean passwordOk = passwordMatches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !passwordOk) {
            // Cùng một thông báo cho "sai email" và "sai mật khẩu".
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
        if (!user.isEnabled()) {
            // Chỉ báo "bị khóa" sau khi mật khẩu đã đúng.
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED);
        }
        return issueTokens(user);
    }

    // noRollbackFor: khi phát hiện token bị dùng lại, ta thu hồi mọi phiên rồi mới ném lỗi;
    // nếu transaction rollback thì việc thu hồi bị mất.
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(RefreshRequest request) {
        RefreshToken stored = refreshTokenRepository
                .findByTokenHash(TokenHasher.sha256Hex(request.refreshToken()))
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN));

        Instant now = clock.instant();
        User user = stored.getUser();

        if (stored.isRevoked()) {
            // Token đã dùng rồi mà vẫn bị gửi lại => nghi bị đánh cắp: đăng xuất mọi thiết bị.
            refreshTokenRepository.revokeAllByUserId(user.getId(), now);
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (stored.isExpired(now)) {
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        if (!user.isEnabled()) {
            refreshTokenRepository.revokeAllByUserId(user.getId(), now);
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED);
        }

        stored.revoke(now);
        return issueTokens(user);
    }

    // Đăng xuất thiết bị hiện tại: thu hồi đúng refresh token được gửi lên.
    // Idempotent: token lạ hoặc đã thu hồi vẫn trả thành công (client dù sao cũng xóa token cục bộ).
    @Transactional
    public void logout(RefreshRequest request) {
        refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(request.refreshToken()))
                .ifPresent(token -> token.revoke(clock.instant()));
    }

    private TokenResponse issueTokens(User user) {
        Instant now = clock.instant();
        String rawRefreshToken = TokenHasher.newOpaqueToken();
        refreshTokenRepository.save(new RefreshToken(
                user,
                TokenHasher.sha256Hex(rawRefreshToken),
                now,
                now.plus(Duration.ofDays(jwtProperties.refreshTokenDays()))));

        return new TokenResponse(
                accessTokenIssuer.issue(user),
                rawRefreshToken,
                "Bearer",
                accessTokenIssuer.ttlSeconds(),
                toProfile(user));
    }

    private boolean passwordMatches(String rawPassword, String hash) {
        if (passwordTooLongForBcrypt(rawPassword)) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, hash);
    }

    private static boolean passwordTooLongForBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static UserProfile toProfile(User user) {
        return new UserProfile(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getPhone(),
                user.getRole().getName().name(),
                user.getCreatedAt());
    }
}
