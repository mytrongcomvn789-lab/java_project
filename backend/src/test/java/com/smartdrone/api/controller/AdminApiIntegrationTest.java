package com.smartdrone.api.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.AuditLogRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// FR-02: kiểm tra 3 API quản trị trên H2 (phân quyền, các quy tắc bảo vệ, validate, audit).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.key=admin-it-test-only-signing-key-at-least-32-characters",
        "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminApiIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder passwords;
    @Autowired AccessTokenIssuer issuer;
    @Autowired AuditLogRepository audit;

    User admin;
    String adminToken;
    String customerToken;

    @BeforeAll
    void setUp() {
        admin = make(RoleName.SYSTEM_ADMIN);
        adminToken = issuer.issue(admin);
        customerToken = issuer.issue(make(RoleName.CUSTOMER));
    }

    @Test
    void onlyAdminCanUseAdminApis() throws Exception {
        for (String path : new String[] {"/v1/admin/accounts", "/v1/admin/roles", "/v1/admin/settings"}) {
            assertEquals(401, call("GET", path, null, null).statusCode(), path);
            assertEquals(403, call("GET", path, customerToken, null).statusCode(), path);
            assertEquals(200, call("GET", path, adminToken, null).statusCode(), path);
        }
        assertEquals(403, call("POST", "/v1/admin/accounts", customerToken, "{}").statusCode());
    }

    @Test
    void createAccountValidatesHashesAndNeverLeaksPassword() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        String body = account(email, "STATION_OPERATOR", "Passw0rdOK");
        var created = call("POST", "/v1/admin/accounts", adminToken, body);
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("STATION_OPERATOR", json(created).get("role").asString());
        assertFalse(created.body().contains("passwordHash"));
        assertFalse(created.body().contains("Passw0rdOK"));

        User saved = users.findByEmail(email).orElseThrow();
        assertNotEquals("Passw0rdOK", saved.getPasswordHash());
        assertTrue(passwords.matches("Passw0rdOK", saved.getPasswordHash()));
        assertTrue(saved.isEnabled());

        assertEquals(409, call("POST", "/v1/admin/accounts", adminToken, body).statusCode());
        assertEquals(400, call("POST", "/v1/admin/accounts", adminToken,
                account(UUID.randomUUID() + "@example.test", "CUSTOMER", "short")).statusCode());
        assertEquals(400, call("POST", "/v1/admin/accounts", adminToken,
                account("not-an-email", "CUSTOMER", "Passw0rdOK")).statusCode());
        assertEquals(400, call("POST", "/v1/admin/accounts", adminToken,
                account(UUID.randomUUID() + "@example.test", "HACKER", "Passw0rdOK")).statusCode());
    }

    @Test
    void lockProtectionsAndTokenInvalidation() throws Exception {
        // Tự khóa mình / tự hạ quyền mình bị chặn.
        assertEquals(409, call("PATCH", "/v1/admin/accounts/" + admin.getId() + "/status", adminToken,
                "{\"enabled\":false}").statusCode());
        var selfDemote = call("PUT", "/v1/admin/accounts/" + admin.getId(), adminToken,
                "{\"fullName\":\"A\",\"phone\":\"0901234567\",\"role\":\"CUSTOMER\"}");
        assertEquals(409, selfDemote.statusCode(), selfDemote.body());

        // Khóa người khác: token cũ của họ bị vô hiệu ngay.
        User target = make(RoleName.DISPATCHER);
        String targetToken = issuer.issue(target);
        assertEquals(200, call("GET", "/v1/stations", targetToken, null).statusCode());
        assertEquals(200, call("PATCH", "/v1/admin/accounts/" + target.getId() + "/status", adminToken,
                "{\"enabled\":false}").statusCode());
        assertEquals(401, call("GET", "/v1/stations", targetToken, null).statusCode());
        assertEquals(200, call("PATCH", "/v1/admin/accounts/" + target.getId() + "/status", adminToken,
                "{\"enabled\":true}").statusCode());
    }

    @Test
    void adminCanLockAndDemoteAnotherAdminAndChangesAreAudited() throws Exception {
        // Có từ 2 admin hoạt động thì khóa/hạ quyền admin khác được (không vi phạm "admin cuối cùng").
        // Chặn tự khóa/tự hạ quyền đã đảm bảo luôn còn ít nhất người đang thao tác.
        User other = make(RoleName.SYSTEM_ADMIN);
        long before = audit.count();
        assertEquals(200, call("PATCH", "/v1/admin/accounts/" + other.getId() + "/status", adminToken,
                "{\"enabled\":false}").statusCode());
        var demote = call("PUT", "/v1/admin/accounts/" + other.getId(), adminToken,
                "{\"fullName\":\"Đã hạ quyền\",\"phone\":\"0901234567\",\"role\":\"CUSTOMER\"}");
        assertEquals(200, demote.statusCode(), demote.body());
        assertEquals("CUSTOMER", json(demote).get("role").asString());
        assertEquals(before + 2, audit.count(), "Mỗi thao tác khóa/đổi vai trò phải ghi một bản audit");
    }

    @Test
    void rolePermissionsMatrixAndAdminLock() throws Exception {
        var catalog = json(call("GET", "/v1/admin/roles", adminToken, null));
        assertEquals(5, catalog.get("roles").size());
        assertTrue(catalog.get("permissions").size() >= 10);
        for (JsonNode r : catalog.get("roles")) {
            if (r.get("role").asString().equals("SYSTEM_ADMIN")) {
                assertTrue(r.get("locked").asBoolean());
                assertEquals(catalog.get("permissions").size(), r.get("permissions").size());
            }
        }
        assertEquals(409, call("PUT", "/v1/admin/roles/SYSTEM_ADMIN/permissions", adminToken,
                "{\"permissions\":[]}").statusCode());
        assertEquals(400, call("PUT", "/v1/admin/roles/CUSTOMER/permissions", adminToken,
                "{\"permissions\":[\"NOPE\"]}").statusCode());
        var ok = call("PUT", "/v1/admin/roles/CUSTOMER/permissions", adminToken,
                "{\"permissions\":[\"ORDER_VIEW\",\"ORDER_CREATE\"]}");
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals(2, json(ok).get("permissions").size());
    }

    @Test
    void settingsAreValidatedAndAudited() throws Exception {
        long before = audit.count();
        assertEquals(400, call("PUT", "/v1/admin/settings", adminToken,
                "{\"values\":{\"drone.battery_warning_percent\":\"999\"}}").statusCode());
        assertEquals(400, call("PUT", "/v1/admin/settings", adminToken,
                "{\"values\":{\"delivery.max_retry\":\"abc\"}}").statusCode());
        assertEquals(400, call("PUT", "/v1/admin/settings", adminToken,
                "{\"values\":{\"system.timezone\":\"Mars/Base\"}}").statusCode());
        assertEquals(400, call("PUT", "/v1/admin/settings", adminToken,
                "{\"values\":{\"unknown.key\":\"1\"}}").statusCode());
        assertEquals(before, audit.count(), "Lưu thất bại thì không được ghi audit");

        var ok = call("PUT", "/v1/admin/settings", adminToken,
                "{\"values\":{\"delivery.max_retry\":\"4\",\"notification.email_enabled\":\"true\"}}");
        assertEquals(200, ok.statusCode(), ok.body());
        boolean found = false;
        for (JsonNode s : json(ok).get("settings")) {
            if (s.get("key").asString().equals("delivery.max_retry")) {
                assertEquals("4", s.get("value").asString());
                found = true;
            }
        }
        assertTrue(found);
        assertTrue(audit.count() > before, "Đổi cấu hình phải ghi audit");
    }

    // ---- tiện ích ----
    private User make(RoleName role) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.test", passwords.encode("AdminItPass1"),
                "Admin IT " + role, roles.findByName(role).orElseThrow()));
    }

    private static String account(String email, String role, String password) throws Exception {
        return JSON.writeValueAsString(Map.of("fullName", "Người dùng thử", "email", email,
                "phone", "0901234567", "role", role, "password", password));
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path))
                .timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static JsonNode json(HttpResponse<String> r) { return JSON.readTree(r.body()); }
}
