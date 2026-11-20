package com.smartdrone.application.apikeys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.smartdrone.application.auth.TokenHasher;
import com.smartdrone.domain.entity.ApiKey;
import com.smartdrone.domain.enums.ApiKeyStatus;
import com.smartdrone.domain.enums.IntegrationScope;
import com.smartdrone.infrastructure.persistence.ApiKeyRepository;

class ApiKeyAuthenticatorTest {
    private final ApiKeyRepository repository = mock(ApiKeyRepository.class);
    private final ApiKeyRateLimiter rateLimiter = mock(ApiKeyRateLimiter.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final ApiKeyAuthenticator authenticator = new ApiKeyAuthenticator(repository, rateLimiter, clock);

    private ApiKey activeKey(String secret, Set<IntegrationScope> scopes) {
        return new ApiKey("Đối tác test", TokenHasher.sha256Hex(secret), scopes, 60,
                UUID.randomUUID(), null, clock.instant());
    }

    @Test
    void correctIdAndSecretReturnsScopes() {
        ApiKey key = activeKey("s3cr3t", Set.of(IntegrationScope.ORDERS_READ));
        UUID id = UUID.randomUUID();
        setId(key, id);
        when(repository.findForAuthentication(id)).thenReturn(Optional.of(key));
        when(rateLimiter.allow(any(), anyInt())).thenReturn(true);

        var result = authenticator.authenticate(id + ".s3cr3t");
        assertEquals(id, result.keyId());
        assertEquals(Set.of(IntegrationScope.ORDERS_READ), result.scopes());
    }

    @Test
    void wrongSecretIsRejected() {
        ApiKey key = activeKey("s3cr3t", Set.of(IntegrationScope.ORDERS_READ));
        UUID id = UUID.randomUUID();
        setId(key, id);
        when(repository.findForAuthentication(id)).thenReturn(Optional.of(key));

        var ex = assertThrows(ApiKeyAuthenticator.ApiKeyAuthException.class,
                () -> authenticator.authenticate(id + ".wrong-secret"));
        assertEquals(ApiKeyAuthenticator.Failure.SECRET_MISMATCH, ex.failure());
    }

    @Test
    void unknownIdIsRejected() {
        UUID id = UUID.randomUUID();
        when(repository.findForAuthentication(id)).thenReturn(Optional.empty());
        var ex = assertThrows(ApiKeyAuthenticator.ApiKeyAuthException.class,
                () -> authenticator.authenticate(id + ".anything"));
        assertEquals(ApiKeyAuthenticator.Failure.NOT_FOUND, ex.failure());
    }

    @Test
    void malformedHeaderIsRejected() {
        var ex1 = assertThrows(ApiKeyAuthenticator.ApiKeyAuthException.class,
                () -> authenticator.authenticate("not-a-valid-header"));
        assertEquals(ApiKeyAuthenticator.Failure.MALFORMED, ex1.failure());
        var ex2 = assertThrows(ApiKeyAuthenticator.ApiKeyAuthException.class,
                () -> authenticator.authenticate(null));
        assertEquals(ApiKeyAuthenticator.Failure.MALFORMED, ex2.failure());
    }

    @Test
    void revokedKeyIsRejectedEvenWithCorrectSecret() {
        ApiKey key = activeKey("s3cr3t", Set.of(IntegrationScope.ORDERS_READ));
        key.revoke(clock.instant());
        UUID id = UUID.randomUUID();
        setId(key, id);
        when(repository.findForAuthentication(id)).thenReturn(Optional.of(key));

        var ex = assertThrows(ApiKeyAuthenticator.ApiKeyAuthException.class,
                () -> authenticator.authenticate(id + ".s3cr3t"));
        assertEquals(ApiKeyAuthenticator.Failure.INACTIVE, ex.failure());
    }

    @Test
    void expiredKeyIsRejected() {
        ApiKey key = new ApiKey("Đối tác test", TokenHasher.sha256Hex("s3cr3t"), Set.of(IntegrationScope.ORDERS_READ),
                60, UUID.randomUUID(), clock.instant().minusSeconds(1), clock.instant().minusSeconds(100));
        UUID id = UUID.randomUUID();
        setId(key, id);
        when(repository.findForAuthentication(id)).thenReturn(Optional.of(key));

        var ex = assertThrows(ApiKeyAuthenticator.ApiKeyAuthException.class,
                () -> authenticator.authenticate(id + ".s3cr3t"));
        assertEquals(ApiKeyAuthenticator.Failure.INACTIVE, ex.failure());
    }

    @Test
    void rateLimitedKeyIsRejected() {
        ApiKey key = activeKey("s3cr3t", Set.of(IntegrationScope.ORDERS_READ));
        UUID id = UUID.randomUUID();
        setId(key, id);
        when(repository.findForAuthentication(id)).thenReturn(Optional.of(key));
        when(rateLimiter.allow(any(), anyInt())).thenReturn(false);

        var ex = assertThrows(ApiKeyAuthenticator.ApiKeyAuthException.class,
                () -> authenticator.authenticate(id + ".s3cr3t"));
        assertEquals(ApiKeyAuthenticator.Failure.RATE_LIMITED, ex.failure());
    }

    private static void setId(ApiKey key, UUID id) {
        try {
            var field = ApiKey.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(key, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static int anyInt() { return org.mockito.ArgumentMatchers.anyInt(); }
}