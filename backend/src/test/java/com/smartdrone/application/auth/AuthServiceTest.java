package com.smartdrone.application.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Mock UserRepository userRepository;
    @Mock RoleRepository roleRepository;
    @Mock RefreshTokenRepository refreshTokenRepository;
    @Mock AccessTokenIssuer accessTokenIssuer;

    // BCrypt thật, strength thấp (4) để test chạy nhanh.
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final JwtProperties properties = new JwtProperties(
            "01234567890123456789012345678901", "SmartDrone", "SmartDroneClients", 15, 7);

    private AuthService service;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, roleRepository, refreshTokenRepository,
                passwordEncoder, accessTokenIssuer, properties, clock);
        when(accessTokenIssuer.issue(any())).thenReturn("access.jwt.value");
        when(accessTokenIssuer.ttlSeconds()).thenReturn(900L);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roleRepository.findByName(RoleName.CUSTOMER)).thenReturn(Optional.of(new Role(RoleName.CUSTOMER)));
    }

    // ---------------------------------------------------------------- register

    @Test
    void registerAlwaysAssignsCustomerRoleAndNormalizesEmail() {
        when(userRepository.existsByEmail("an@example.com")).thenReturn(false);

        UserProfile profile = service.register(
                new RegisterRequest("  An@Example.COM ", "Passw0rdOK", "  Nguyễn Văn An ", "0901234567"));

        assertEquals("CUSTOMER", profile.role());
        assertEquals("an@example.com", profile.email());
        assertEquals("Nguyễn Văn An", profile.fullName());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertEquals(RoleName.CUSTOMER, saved.getValue().getRole().getName());
        // mật khẩu được băm, không lưu dạng rõ
        assertNotEquals("Passw0rdOK", saved.getValue().getPasswordHash());
        assertTrue(passwordEncoder.matches("Passw0rdOK", saved.getValue().getPasswordHash()));
        // chỉ tra role CUSTOMER, không bao giờ tra role khác
        verify(roleRepository).findByName(RoleName.CUSTOMER);
        verify(roleRepository, never()).findByName(RoleName.SYSTEM_ADMIN);
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("an@example.com")).thenReturn(true);

        ApiException ex = assertThrows(ApiException.class, () -> service.register(
                new RegisterRequest("an@example.com", "Passw0rdOK", "An", null)));

        assertEquals(ErrorCode.EMAIL_ALREADY_EXISTS, ex.getErrorCode());
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerMapsUniqueIndexViolationToConflict() {
        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("dup"));

        ApiException ex = assertThrows(ApiException.class, () -> service.register(
                new RegisterRequest("an@example.com", "Passw0rdOK", "An", null)));

        assertEquals(ErrorCode.EMAIL_ALREADY_EXISTS, ex.getErrorCode());
    }

    @Test
    void registerRejectsPasswordOver72Bytes() {
        // 30 ký tự "ế" = 90 byte UTF-8, vẫn dưới 72 ký tự nhưng vượt giới hạn 72 byte của BCrypt
        String longPassword = "ế".repeat(30) + "a1";

        ApiException ex = assertThrows(ApiException.class, () -> service.register(
                new RegisterRequest("an@example.com", longPassword, "An", null)));

        assertEquals(ErrorCode.VALIDATION_ERROR, ex.getErrorCode());
    }

    // ---------------------------------------------------------------- login

    @Test
    void loginSucceedsAndStoresOnlyHashOfRefreshToken() {
        User user = newUser("an@example.com", "Passw0rdOK", true);
        when(userRepository.findByEmail("an@example.com")).thenReturn(Optional.of(user));

        TokenResponse response = service.login(new LoginRequest(" AN@example.com", "Passw0rdOK"));

        assertEquals("access.jwt.value", response.accessToken());
        assertEquals("Bearer", response.tokenType());
        assertEquals(900L, response.expiresIn());
        assertEquals("CUSTOMER", response.user().role());
        assertNotNull(response.refreshToken());

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(saved.capture());
        RefreshToken stored = saved.getValue();
        assertNotEquals(response.refreshToken(), stored.getTokenHash());
        assertEquals(TokenHasher.sha256Hex(response.refreshToken()), stored.getTokenHash());
        assertEquals(64, stored.getTokenHash().length());
        assertEquals(NOW.plusSeconds(7 * 24 * 3600), stored.getExpiresAt());
    }

    @Test
    void loginWrongPasswordAndUnknownEmailGiveSameError() {
        User user = newUser("an@example.com", "Passw0rdOK", true);
        when(userRepository.findByEmail("an@example.com")).thenReturn(Optional.of(user));
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        ApiException wrongPassword = assertThrows(ApiException.class,
                () -> service.login(new LoginRequest("an@example.com", "SaiMatKhau1")));
        ApiException unknownEmail = assertThrows(ApiException.class,
                () -> service.login(new LoginRequest("nobody@example.com", "Passw0rdOK")));

        assertEquals(ErrorCode.INVALID_CREDENTIALS, wrongPassword.getErrorCode());
        assertEquals(ErrorCode.INVALID_CREDENTIALS, unknownEmail.getErrorCode());
        assertEquals(wrongPassword.getMessage(), unknownEmail.getMessage());
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void loginRejectsLockedAccountOnlyAfterCorrectPassword() {
        User user = newUser("an@example.com", "Passw0rdOK", false);
        when(userRepository.findByEmail("an@example.com")).thenReturn(Optional.of(user));

        ApiException locked = assertThrows(ApiException.class,
                () -> service.login(new LoginRequest("an@example.com", "Passw0rdOK")));
        ApiException wrong = assertThrows(ApiException.class,
                () -> service.login(new LoginRequest("an@example.com", "SaiMatKhau1")));

        assertEquals(ErrorCode.ACCOUNT_LOCKED, locked.getErrorCode());
        assertEquals(ErrorCode.INVALID_CREDENTIALS, wrong.getErrorCode());
    }

    // ---------------------------------------------------------------- refresh

    @Test
    void refreshRotatesToken() {
        User user = newUser("an@example.com", "Passw0rdOK", true);
        RefreshToken old = storedToken(user, "old-raw-token", NOW.plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex("old-raw-token")))
                .thenReturn(Optional.of(old));

        TokenResponse response = service.refresh(new RefreshRequest("old-raw-token"));

        assertTrue(old.isRevoked(), "token cũ phải bị thu hồi");
        assertNotEquals("old-raw-token", response.refreshToken(), "phải cấp token mới");
        assertEquals("access.jwt.value", response.accessToken());
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void refreshRejectsUnknownToken() {
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class,
                () -> service.refresh(new RefreshRequest("does-not-exist")));

        assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, ex.getErrorCode());
    }

    @Test
    void refreshRejectsExpiredToken() {
        User user = newUser("an@example.com", "Passw0rdOK", true);
        RefreshToken expired = storedToken(user, "expired-token", NOW.minusSeconds(1));
        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex("expired-token")))
                .thenReturn(Optional.of(expired));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.refresh(new RefreshRequest("expired-token")));

        assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, ex.getErrorCode());
        assertFalse(expired.isRevoked());
    }

    @Test
    void refreshReuseOfRevokedTokenRevokesAllSessions() {
        User user = newUser("an@example.com", "Passw0rdOK", true);
        RefreshToken used = storedToken(user, "used-token", NOW.plusSeconds(3600));
        used.revoke(NOW.minusSeconds(60));
        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex("used-token")))
                .thenReturn(Optional.of(used));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.refresh(new RefreshRequest("used-token")));

        assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, ex.getErrorCode());
        verify(refreshTokenRepository).revokeAllByUserId(eq(user.getId()), eq(NOW));
        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void refreshRejectsLockedAccountAndRevokesSessions() {
        User user = newUser("an@example.com", "Passw0rdOK", false);
        RefreshToken token = storedToken(user, "locked-token", NOW.plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex("locked-token")))
                .thenReturn(Optional.of(token));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.refresh(new RefreshRequest("locked-token")));

        assertEquals(ErrorCode.ACCOUNT_LOCKED, ex.getErrorCode());
        verify(refreshTokenRepository).revokeAllByUserId(eq(user.getId()), eq(NOW));
    }

    // ---------------------------------------------------------------- logout

    @Test
    void logoutRevokesTokenAndIsIdempotent() {
        User user = newUser("an@example.com", "Passw0rdOK", true);
        RefreshToken token = storedToken(user, "my-token", NOW.plusSeconds(3600));
        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex("my-token")))
                .thenReturn(Optional.of(token));
        when(refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex("unknown")))
                .thenReturn(Optional.empty());

        service.logout(new RefreshRequest("my-token"));
        assertTrue(token.isRevoked());
        assertEquals(NOW, token.getRevokedAt());

        service.logout(new RefreshRequest("my-token"));   // gọi lần hai vẫn không lỗi
        service.logout(new RefreshRequest("unknown"));    // token lạ cũng không lỗi
    }

    // ---------------------------------------------------------------- helpers

    private User newUser(String email, String rawPassword, boolean enabled) {
        User user = new User(email, passwordEncoder.encode(rawPassword), "Nguyễn Văn An", new Role(RoleName.CUSTOMER));
        user.setEnabled(enabled);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }

    private RefreshToken storedToken(User user, String rawToken, Instant expiresAt) {
        return new RefreshToken(user, TokenHasher.sha256Hex(rawToken), NOW.minusSeconds(120), expiresAt);
    }
}
