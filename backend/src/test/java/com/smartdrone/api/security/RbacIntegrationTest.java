package com.smartdrone.api.security;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.users.UserAccessService;
import com.smartdrone.application.users.dto.UpdateUserAccessRequest;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import rbacfixtures.BusinessEndpointProbeController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=${RBAC_TEST_DATABASE_URL:jdbc:h2:mem:rbac-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1}",
        "spring.datasource.driver-class-name=${RBAC_TEST_DATABASE_DRIVER:org.h2.Driver}",
        "spring.datasource.username=${RBAC_TEST_DATABASE_USER:sa}",
        "spring.datasource.password=${RBAC_TEST_DATABASE_PASSWORD:}",
        "app.jwt.key=apddmp18-test-only-signing-key-at-least-32-characters",
        "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
@Import(BusinessEndpointProbeController.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RbacIntegrationTest {
    private static final String PASSWORD = "RbacTestPassword18";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<RoleName, String> tokens = new EnumMap<>(RoleName.class);
    private final Map<RoleName, UUID> ids = new EnumMap<>(RoleName.class);

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder passwords;
    @Autowired AccessTokenIssuer tokenIssuer;
    @Autowired JwtEncoder encoder;
    @Autowired UserAccessService accessService;

    @BeforeAll
    void createFiveActors() {
        for (RoleName role : RoleName.values()) {
            User user = createUser(role);
            ids.put(role, user.getId());
            tokens.put(role, tokenIssuer.issue(user));
        }
    }

    static List<String[]> matrix() throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(
                RbacIntegrationTest.class.getResourceAsStream("/rbac-matrix.csv"), StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                    .map(line -> line.split(",")).toList();
        }
    }

    static Stream<Arguments> actorMatrix() throws Exception {
        return matrix().stream().flatMap(row -> Arrays.stream(RoleName.values()).map(role -> {
            boolean allowed = Arrays.asList(row[3].split("\\|")).contains(role.name());
            return Arguments.of(row[0], row[1], role, allowed ? Integer.parseInt(row[2]) : 403);
        }));
    }

    static Stream<Arguments> anonymousMatrix() throws Exception {
        return matrix().stream().map(row -> Arguments.of(row[0], row[1]));
    }

    @ParameterizedTest(name = "{0} {1}: {2} -> {3}")
    @MethodSource("actorMatrix")
    void enforcesIndependentActorMatrix(String method, String path, RoleName role, int expected) throws Exception {
        var response = call(method, path, tokens.get(role), null);
        assertEquals(expected, response.statusCode(), () -> method + " " + path + " " + role + ": " + response.body());
        if (expected == 403) assertProblem(response, 403, "FORBIDDEN");
    }

    @ParameterizedTest(name = "anonymous {0} {1} -> 401")
    @MethodSource("anonymousMatrix")
    void allProtectedRoutesRequireAuthentication(String method, String path) throws Exception {
        assertProblem(call(method, path, null, null), 401, "AUTH_REQUIRED");
    }

    @ParameterizedTest
    @EnumSource(RoleName.class)
    void unknownRoutesWrongMethodsAndIntegrationAreDenied(RoleName role) throws Exception {
        String token = tokens.get(role);
        assertProblem(call("GET", "/v1/not-in-policy", token, null), 403, "FORBIDDEN");
        assertProblem(call("POST", "/v1/users", token, "{}"), 403, "FORBIDDEN");
        assertProblem(call("POST", "/v1/integration/orders/123/events", token, "{}"), 403, "FORBIDDEN");
        assertProblem(call("DELETE", "/v1/me/permissions", token, null), 403, "FORBIDDEN");
    }

    @Test
    void healthAndSwaggerRemainPublicAndRegistrationCannotChooseRole() throws Exception {
        assertEquals(200, call("GET", "/health", null, null).statusCode());
        assertEquals(200, call("GET", "/v3/api-docs", null, null).statusCode());
        String email = UUID.randomUUID() + "@example.test";
        var registered = call("POST", "/v1/auth/register", null, JSON.writeValueAsString(Map.of(
                "email", email, "password", PASSWORD, "fullName", "RBAC HTTP test",
                "role", "SYSTEM_ADMIN", "enabled", true)));
        assertEquals(201, registered.statusCode(), registered.body());
        assertEquals("CUSTOMER", json(registered).get("role").asString());
        var login = login(email);
        String token = login.get("accessToken").asString();
        assertProblem(call("GET", "/v1/users", token, null), 403, "FORBIDDEN");
        assertProblem(call("PATCH", "/v1/users/" + json(registered).get("id").asString() + "/access",
                token, "{\"role\":\"SYSTEM_ADMIN\"}"), 403, "FORBIDDEN");
        assertEquals(204, call("POST", "/v1/auth/logout", null,
                JSON.writeValueAsString(Map.of("refreshToken", login.get("refreshToken").asString()))).statusCode());
    }

    @ParameterizedTest
    @EnumSource(RoleName.class)
    void onlyAdminCanGrantEachRoleAndOldSessionsStayRevoked(RoleName newRole) throws Exception {
        // Vai trò gốc luôn khác vai trò đích để mọi case đều thực sự đổi quyền.
        User target = createUser(newRole == RoleName.CUSTOMER ? RoleName.DISPATCHER : RoleName.CUSTOMER);
        RoleName originalRole = target.getRole().getName();
        var session = login(target.getEmail());
        String oldToken = session.get("accessToken").asString();
        String path = "/v1/users/" + target.getId() + "/access";
        String body = JSON.writeValueAsString(Map.of("role", newRole.name()));
        for (RoleName actor : RoleName.values()) {
            if (actor != RoleName.SYSTEM_ADMIN) {
                assertProblem(call("PATCH", path, tokens.get(actor), body), 403, "FORBIDDEN");
            }
        }
        assertProblem(call("PATCH", path, null, body), 401, "AUTH_REQUIRED");
        var changed = call("PATCH", path, admin(), body);
        assertEquals(200, changed.statusCode(), changed.body());
        assertEquals(newRole.name(), json(changed).get("role").asString());
        assertProblem(call("GET", "/v1/me/permissions", oldToken, null), 401, "AUTH_REQUIRED");
        assertProblem(call("POST", "/v1/auth/refresh", null,
                JSON.writeValueAsString(Map.of("refreshToken", session.get("refreshToken").asString()))),
                401, "INVALID_REFRESH_TOKEN");
        var current = login(target.getEmail());
        var permissions = call("GET", "/v1/me/permissions", current.get("accessToken").asString(), null);
        assertEquals(200, permissions.statusCode(), permissions.body());
        assertEquals(newRole.name(), json(permissions).get("role").asString());
        // Cấp lại role cũ vẫn không được làm sống lại JWT đã thu hồi (accessVersion).
        assertEquals(200, call("PATCH", path, admin(),
                JSON.writeValueAsString(Map.of("role", originalRole.name()))).statusCode());
        assertProblem(call("GET", "/v1/me/permissions", oldToken, null), 401, "AUTH_REQUIRED");
    }

    @Test
    void lockingAndUnlockingDoesNotReviveOldToken() throws Exception {
        User target = createUser(RoleName.DISPATCHER);
        var session = login(target.getEmail());
        String oldToken = session.get("accessToken").asString();
        String path = "/v1/users/" + target.getId() + "/access";
        assertEquals(200, call("PATCH", path, admin(), "{\"enabled\":false}").statusCode());
        assertProblem(call("GET", "/v1/me/permissions", oldToken, null), 401, "AUTH_REQUIRED");
        assertProblem(call("POST", "/v1/auth/login", null,
                JSON.writeValueAsString(Map.of("email", target.getEmail(), "password", PASSWORD))), 403, "ACCOUNT_LOCKED");
        assertEquals(200, call("PATCH", path, admin(), "{\"enabled\":true}").statusCode());
        assertProblem(call("GET", "/v1/me/permissions", oldToken, null), 401, "AUTH_REQUIRED");
        assertEquals(200, call("GET", "/v1/me/permissions", login(target.getEmail()).get("accessToken").asString(), null).statusCode());
    }

    @Test
    void identicalUpdatePreservesActiveSessions() throws Exception {
        User target = createUser(RoleName.CUSTOMER);
        var session = login(target.getEmail());
        assertEquals(200, call("PATCH", "/v1/users/" + target.getId() + "/access", admin(),
                "{\"role\":\"CUSTOMER\",\"enabled\":true}").statusCode());
        assertEquals(200, call("GET", "/v1/me/permissions", session.get("accessToken").asString(), null).statusCode());
        assertEquals(200, call("POST", "/v1/auth/refresh", null,
                JSON.writeValueAsString(Map.of("refreshToken", session.get("refreshToken").asString()))).statusCode());
    }

    @Test
    void concurrentAdminsCannotBothRevokeEachOtherUsingStaleAuthority() throws Exception {
        User first = createUser(RoleName.SYSTEM_ADMIN);
        User second = createUser(RoleName.SYSTEM_ADMIN);
        String firstToken = tokenIssuer.issue(first);
        String secondToken = tokenIssuer.issue(second);
        var start = new CyclicBarrier(2);
        Callable<Integer> revokeSecond = () -> {
            start.await(10, TimeUnit.SECONDS);
            return call("PATCH", "/v1/users/" + second.getId() + "/access", firstToken,
                    "{\"role\":\"CUSTOMER\"}").statusCode();
        };
        Callable<Integer> revokeFirst = () -> {
            start.await(10, TimeUnit.SECONDS);
            return call("PATCH", "/v1/users/" + first.getId() + "/access", secondToken,
                    "{\"role\":\"CUSTOMER\"}").statusCode();
        };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var left = pool.submit(revokeSecond);
            var right = pool.submit(revokeFirst);
            List<Integer> statuses = List.of(left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS));
            assertEquals(1L, statuses.stream().filter(status -> status == 200).count(), statuses.toString());
            assertTrue(statuses.stream().allMatch(status -> status == 200 || status == 401 || status == 403), statuses.toString());
        }
        long adminsRemaining = Stream.of(first.getId(), second.getId())
                .map(id -> users.findWithRoleById(id).orElseThrow())
                .filter(user -> user.getRole().getName() == RoleName.SYSTEM_ADMIN && user.isEnabled()).count();
        assertEquals(1L, adminsRemaining);
    }

    @Test
    void refusesSelfLockSelfDemotionAndInvalidAccessRequests() throws Exception {
        String self = "/v1/users/" + ids.get(RoleName.SYSTEM_ADMIN) + "/access";
        assertProblem(call("PATCH", self, admin(), "{\"role\":\"CUSTOMER\"}"), 409, "SELF_ACCESS_CHANGE");
        assertProblem(call("PATCH", self, admin(), "{\"enabled\":false}"), 409, "SELF_ACCESS_CHANGE");
        assertProblem(call("PATCH", self, admin(), "{}"), 400, "VALIDATION_ERROR");
        assertProblem(call("PATCH", self, admin(), "{\"role\":\"ADMIN\"}"), 400, "VALIDATION_ERROR");
        assertProblem(call("PATCH", self, admin(), "{\"role\":\"SUPERUSER\"}"), 400, "VALIDATION_ERROR");
        assertProblem(call("PATCH", "/v1/users/" + UUID.randomUUID() + "/access", admin(),
                "{\"enabled\":false}"), 404, "RESOURCE_NOT_FOUND");
        assertEquals(200, call("PATCH", self, admin(), "{\"role\":\"SYSTEM_ADMIN\",\"enabled\":true}").statusCode());
        assertEquals(200, call("GET", "/v1/roles", admin(), null).statusCode());
    }

    @Test
    void catalogAndPaginationAreSafeAndBounded() throws Exception {
        var catalog = call("GET", "/v1/roles", admin(), null);
        assertEquals(5, json(catalog).size());
        var page = call("GET", "/v1/users?page=1&pageSize=2", admin(), null);
        assertEquals(200, page.statusCode(), page.body());
        assertEquals(2, json(page).get("items").size());
        assertFalse(page.body().contains("passwordHash"));
        assertFalse(page.body().contains("refreshToken"));
        assertFalse(page.body().contains("accessVersion"));
        assertEquals("no-store", page.headers().firstValue("Cache-Control").orElse(""));
        for (String query : List.of("page=0", "page=-1", "pageSize=0", "pageSize=101")) {
            assertProblem(call("GET", "/v1/users?" + query, admin(), null), 400, "VALIDATION_ERROR");
        }
    }

    @Test
    void rejectsMalformedUnknownRoleDeletedAccountAndStaleClaims() throws Exception {
        assertProblem(call("GET", "/v1/me/permissions", "invalid.jwt.token", null), 401, "AUTH_REQUIRED");
        User user = createUser(RoleName.CUSTOMER);
        for (Object role : List.of("SUPERUSER", "CUSTOMER SYSTEM_ADMIN", List.of("CUSTOMER", "SYSTEM_ADMIN"), "SYSTEM_ADMIN")) {
            assertProblem(call("GET", "/v1/me/permissions", signedClaims(user.getId().toString(), role), null),
                    401, "AUTH_REQUIRED");
        }
        assertProblem(call("GET", "/v1/me/permissions", signedClaims("not-a-uuid", "CUSTOMER"), null), 401, "AUTH_REQUIRED");
        String deletedToken = tokenIssuer.issue(user);
        users.deleteById(user.getId());
        assertProblem(call("GET", "/v1/me/permissions", deletedToken, null), 401, "AUTH_REQUIRED");
    }

    @Test
    void legacyJwtWorksOnlyUntilItsAccountAccessChanges() throws Exception {
        User user = createUser(RoleName.CUSTOMER);
        String legacy = signedClaims(user.getId().toString(), "CUSTOMER");
        assertEquals(200, call("GET", "/v1/me/permissions", legacy, null).statusCode());
        String path = "/v1/users/" + user.getId() + "/access";
        assertEquals(200, call("PATCH", path, admin(), "{\"enabled\":false}").statusCode());
        assertEquals(200, call("PATCH", path, admin(), "{\"enabled\":true}").statusCode());
        assertProblem(call("GET", "/v1/me/permissions", legacy, null), 401, "AUTH_REQUIRED");
    }

    @Test
    void corsPreflightWorksWithoutToken() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base() + "/v1/users"))
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertEquals("http://localhost:5173", response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
    }

    @Test
    @WithMockUser(roles = "CUSTOMER")
    void directServiceCallsCannotBypassUrlSecurity() {
        assertThrows(AccessDeniedException.class, () -> accessService.list(1, 20));
        assertThrows(AccessDeniedException.class, () -> accessService.update(ids.get(RoleName.CUSTOMER),
                ids.get(RoleName.DISPATCHER), new UpdateUserAccessRequest(RoleName.SYSTEM_ADMIN, null)));
    }

    private User createUser(RoleName role) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", passwords.encode(PASSWORD),
                "RBAC test " + role.name(), roles.findByName(role).orElseThrow()));
    }

    private JsonNode login(String email) throws Exception {
        var response = call("POST", "/v1/auth/login", null,
                JSON.writeValueAsString(Map.of("email", email, "password", PASSWORD)));
        assertEquals(200, response.statusCode(), response.body());
        return json(response);
    }

    private String signedClaims(String subject, Object role) {
        var claims = JwtClaimsSet.builder().subject(subject).issuer("SmartDrone")
                .audience(List.of("SmartDroneClients")).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600)).claim("role", role).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode json(HttpResponse<String> response) { return JSON.readTree(response.body()); }
    private String admin() { return tokens.get(RoleName.SYSTEM_ADMIN); }
    private String base() { return "http://localhost:" + port + "/api"; }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertEquals(code, json(response).get("code").asString(), response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
        assertNotNull(json(response).get("traceId"));
        assertEquals(status, json(response).get("status").asInt());
    }
}
