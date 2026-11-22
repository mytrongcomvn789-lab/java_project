package com.smartdrone.api.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 2.4 (NFR-07): thao tác quan trọng được ghi audit và admin tra cứu được qua GET /v1/audit-events. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:audit-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
    "app.jwt.key=audit-test-signing-key-longer-than-32-characters",
    "logging.file.name=",
    "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class AuditIntegrationTest {
    private static final String PASSWORD = "AuditTestPassword24";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder passwords;
    @Autowired AccessTokenIssuer tokens;

    private User user(RoleName role) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@audit.test", passwords.encode(PASSWORD),
                "Audit tester", roles.findByName(role).orElseThrow()));
    }

    private HttpResponse<String> call(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return call("POST", "/auth/login", null, Map.of("email", email, "password", password));
    }

    /** Tra audit bằng tài khoản admin, trả về mảng items. */
    private JsonNode audit(String admin, String query) throws Exception {
        var response = call("GET", "/audit-events?" + query, admin, null);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        return JSON.readTree(response.body()).get("items");
    }

    private static String q(Object value) {
        return URLEncoder.encode(value.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void failedLoginIsKeptEvenThoughRequestFailsAndLinksToTraceId() throws Exception {
        String admin = tokens.issue(user(RoleName.SYSTEM_ADMIN));
        User victim = user(RoleName.CUSTOMER);

        var failed = login(victim.getEmail(), "wrong-password-123");
        assertEquals(401, failed.statusCode(), failed.body());

        JsonNode items = audit(admin, "action=LOGIN_FAILED&targetId=" + q(victim.getId()));
        assertEquals(1, items.size(), items.toString());
        JsonNode event = items.get(0);
        assertEquals(victim.getId().toString(), event.get("actorId").asString());
        assertEquals("CUSTOMER", event.get("actorRole").asString());
        assertEquals(victim.getEmail(), event.get("actorEmail").asString());
        assertEquals("USER", event.get("targetType").asString());
        assertTrue(event.get("detail").asString().contains("INVALID_CREDENTIALS"));
        assertFalse(event.get("detail").asString().contains("wrong-password-123"), "không được lưu mật khẩu");
        assertFalse(event.get("ipAddress").isNull());
        // từ dòng audit tra ngược ra đúng request (và log) bằng traceId
        assertEquals(failed.headers().firstValue("X-Trace-Id").orElseThrow(), event.get("traceId").asString());
    }

    @Test
    void loginWithUnknownEmailHasNoActor() throws Exception {
        String admin = tokens.issue(user(RoleName.SYSTEM_ADMIN));
        String email = UUID.randomUUID() + "@nobody.test";

        assertEquals(401, login(email, "whatever-123").statusCode());

        JsonNode match = null;
        for (JsonNode e : audit(admin, "action=LOGIN_FAILED&pageSize=100")) {
            if (e.get("detail").asString().contains(email)) match = e;
        }
        assertNotNull(match, "phải có dòng LOGIN_FAILED cho email không tồn tại");
        assertTrue(match.get("actorId").isNull());
        assertTrue(match.get("targetId").isNull());
    }

    @Test
    void successfulLoginAndLogoutAreRecorded() throws Exception {
        String admin = tokens.issue(user(RoleName.SYSTEM_ADMIN));
        User customer = user(RoleName.CUSTOMER);

        var ok = login(customer.getEmail(), PASSWORD);
        assertEquals(200, ok.statusCode(), ok.body());
        String refresh = JSON.readTree(ok.body()).get("refreshToken").asString();
        assertEquals(204, call("POST", "/auth/logout", null, Map.of("refreshToken", refresh)).statusCode());
        // đăng xuất lại bằng token đã thu hồi: vẫn thành công nhưng không ghi thêm
        assertEquals(204, call("POST", "/auth/logout", null, Map.of("refreshToken", refresh)).statusCode());

        JsonNode items = audit(admin, "targetId=" + q(customer.getId()));
        assertEquals(2, items.size(), items.toString());
        assertEquals("LOGOUT", items.get(0).get("action").asString()); // mới nhất trước
        assertEquals("LOGIN_SUCCEEDED", items.get(1).get("action").asString());
    }

    @Test
    void adminAccessChangeAndStationCreationRecordTheActor() throws Exception {
        User adminUser = user(RoleName.SYSTEM_ADMIN);
        String admin = tokens.issue(adminUser);
        User target = user(RoleName.CUSTOMER);
        User manager = user(RoleName.LOGISTICS_MANAGER);

        assertEquals(200, call("PATCH", "/users/" + target.getId() + "/access", admin,
                Map.of("role", "DISPATCHER")).statusCode());
        JsonNode access = audit(admin, "action=USER_ACCESS_CHANGED&targetId=" + q(target.getId())).get(0);
        assertEquals(adminUser.getId().toString(), access.get("actorId").asString());
        assertEquals("SYSTEM_ADMIN", access.get("actorRole").asString());
        assertTrue(access.get("detail").asString().contains("role=CUSTOMER->DISPATCHER"));

        var created = call("POST", "/stations", tokens.issue(manager), Map.of(
                "name", "Trạm audit", "latitude", 10.77, "longitude", 106.70, "capacity", 5));
        assertEquals(201, created.statusCode(), created.body());
        String stationId = JSON.readTree(created.body()).get("id").asString();
        JsonNode station = audit(admin, "targetType=STATION&targetId=" + q(stationId)).get(0);
        assertEquals("STATION_CREATED", station.get("action").asString());
        assertEquals(manager.getId().toString(), station.get("actorId").asString()); // lấy từ SecurityContext
        assertEquals("LOGISTICS_MANAGER", station.get("actorRole").asString());
    }

    @Test
    void onlyAdminAndLogisticsManagerCanSearchAndFiltersAreValidated() throws Exception {
        String admin = tokens.issue(user(RoleName.SYSTEM_ADMIN));

        assertEquals(200, call("GET", "/audit-events", tokens.issue(user(RoleName.LOGISTICS_MANAGER)), null)
                .statusCode());
        assertEquals(403, call("GET", "/audit-events", tokens.issue(user(RoleName.DISPATCHER)), null)
                .statusCode());
        assertEquals(401, call("GET", "/audit-events", null, null).statusCode());
        assertEquals(400, call("GET", "/audit-events?pageSize=101", admin, null).statusCode());
        assertEquals(400, call("GET", "/audit-events?actorId=not-a-uuid", admin, null).statusCode());
        Instant now = Instant.now();
        assertEquals(400, call("GET", "/audit-events?from=" + q(now) + "&to=" + q(now.minusSeconds(60)),
                admin, null).statusCode());
        // khoảng thời gian trong tương lai: không có gì
        assertEquals(0, audit(admin, "from=" + q(now.plusSeconds(3600))).size());
    }
}
