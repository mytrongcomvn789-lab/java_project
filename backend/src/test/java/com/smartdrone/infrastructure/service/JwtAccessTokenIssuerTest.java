package com.smartdrone.infrastructure.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

import com.smartdrone.api.config.JwtConfig;
import com.smartdrone.application.auth.JwtProperties;
import com.smartdrone.domain.entity.Role;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;

class JwtAccessTokenIssuerTest {

    private static final String KEY = "01234567890123456789012345678901-test-key";

    private final JwtConfig config = new JwtConfig();
    private final JwtProperties properties = new JwtProperties(KEY, "SmartDrone", "SmartDroneClients", 15, 7);

    @Test
    void issuedTokenCanBeDecodedWithExpectedClaims() {
        User user = dispatcher();
        String token = issuer(Clock.systemUTC(), properties).issue(user);

        Jwt jwt = config.jwtDecoder(properties).decode(token);

        assertEquals(user.getId().toString(), jwt.getSubject());
        assertEquals("DISPATCHER", jwt.getClaimAsString("role"));
        assertEquals("dp@example.com", jwt.getClaimAsString("email"));
        assertEquals("SmartDrone", jwt.getClaimAsString("iss"));
        assertTrue(jwt.getAudience().contains("SmartDroneClients"));
        assertEquals(Duration.ofMinutes(15),
                Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()));
    }

    @Test
    void ttlSecondsMatchesConfiguration() {
        assertEquals(900L, issuer(Clock.systemUTC(), properties).ttlSeconds());
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = issuer(Clock.systemUTC(), properties).issue(dispatcher());
        // đổi một ký tự ở phần payload
        String[] parts = token.split("\\.");
        char first = parts[1].charAt(0);
        parts[1] = (first == 'A' ? 'B' : 'A') + parts[1].substring(1);
        String tampered = String.join(".", parts);

        JwtDecoder decoder = config.jwtDecoder(properties);
        assertThrows(JwtException.class, () -> decoder.decode(tampered));
    }

    @Test
    void expiredTokenIsRejected() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        String token = issuer(past, properties).issue(dispatcher());

        JwtException ex = assertThrows(JwtException.class, () -> config.jwtDecoder(properties).decode(token));
        assertTrue(ex.getMessage().toLowerCase().contains("expired"));
    }

    @Test
    void tokenWithWrongAudienceOrKeyIsRejected() {
        String token = issuer(Clock.systemUTC(), properties).issue(dispatcher());

        JwtProperties otherAudience = new JwtProperties(KEY, "SmartDrone", "AnotherApp", 15, 7);
        assertThrows(JwtException.class, () -> config.jwtDecoder(otherAudience).decode(token));

        JwtProperties otherKey = new JwtProperties("another-key-another-key-another-key-0000", "SmartDrone", "SmartDroneClients", 15, 7);
        assertThrows(JwtException.class, () -> config.jwtDecoder(otherKey).decode(token));
    }

    @Test
    void shortKeyIsRejectedAtStartup() {
        JwtProperties shortKey = new JwtProperties("too-short", "SmartDrone", "SmartDroneClients", 15, 7);
        assertThrows(IllegalStateException.class, () -> config.jwtEncoder(shortKey));
    }

    private JwtAccessTokenIssuer issuer(Clock clock, JwtProperties props) {
        return new JwtAccessTokenIssuer(config.jwtEncoder(props), props, clock);
    }

    private User dispatcher() {
        User user = new User("dp@example.com", "hash", "Điều phối viên", new Role(RoleName.DISPATCHER));
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
