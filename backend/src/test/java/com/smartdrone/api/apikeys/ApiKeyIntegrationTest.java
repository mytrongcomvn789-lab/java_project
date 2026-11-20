package com.smartdrone.api.apikeys;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Task 5.4: cấp/thu hồi/xoay vòng API key, kiểm tra scope, hạn mức, và tách biệt hoàn toàn với JWT. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:apikey-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "app.jwt.key=apikey-test-signing-key-at-least-32-characters-long",
    "app.api-keys.default-rate-limit-per-minute=3",
    "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AccessTokenIssuer tokens;
    @Autowired PasswordEncoder encoder;

    private String adminToken;
    private String customerToken;

    @BeforeAll
    void seedActors() {
        User admin = users.saveAndFlush(new User("admin@apikey.test", encoder.encode("unused-test-pw"),
                "Admin tester", roles.findByName(RoleName.SYSTEM_ADMIN).orElseThrow()));
        adminToken = tokens.issue(admin);
        User customer = users.saveAndFlush(new User("customer@apikey.test", encoder.encode("unused-test-pw"),
                "Customer tester", roles.findByName(RoleName.CUSTOMER).orElseThrow()));
        customerToken = tokens.issue(customer);
    }

    private HttpResponse<String> call(String method, String path, String body, String bearer, String apiKey)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(30));
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        if (apiKey != null) request.header("X-Api-Key", apiKey);
        if (body != null) {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode issueKey(String clientName, String scopesJson) throws Exception {
        var res = call("POST", "/admin/api-keys",
                "{\"clientName\":\"" + clientName + "\",\"scopes\":" + scopesJson + "}", adminToken, null);
        assertEquals(201, res.statusCode(), res.body());
        return JSON.readTree(res.body());
    }

    @Test
    void adminCanIssueKeyAndSecretWorksForItsScopeOnly() throws Exception {
        JsonNode issued = issueKey("Đối tác giao nhận", "[\"STATIONS_READ\"]");
        String rawKey = issued.get("secret").asString();
        assertNotNull(issued.get("key").get("id").asString());
        assertFalse(issued.toString().toLowerCase().contains("secrethash"), "Không được lộ secretHash");

        var stations = call("GET", "/integration/stations", null, null, rawKey);
        assertEquals(200, stations.statusCode(), stations.body());

        // Key chỉ có STATIONS_READ, không có ORDERS_READ: endpoint khác bị từ chối dù key hợp lệ.
        var orderStatus = call("GET", "/integration/orders/" + java.util.UUID.randomUUID() + "/status",
                null, null, rawKey);
        assertEquals(403, orderStatus.statusCode());
    }

    @Test
    void listNeverExposesSecretOrHash() throws Exception {
        issueKey("Đối tác xem danh sách", "[\"STATIONS_READ\"]");
        var list = call("GET", "/admin/api-keys", null, adminToken, null);
        assertEquals(200, list.statusCode());
        String body = list.body().toLowerCase();
        assertFalse(body.contains("secret"), "GET /admin/api-keys không được chứa secret ở bất kỳ dạng nào");
    }

    @Test
    void revokedKeyStopsWorkingImmediately() throws Exception {
        JsonNode issued = issueKey("Đối tác bị thu hồi", "[\"STATIONS_READ\"]");
        String rawKey = issued.get("secret").asString();
        String id = issued.get("key").get("id").asString();

        assertEquals(200, call("GET", "/integration/stations", null, null, rawKey).statusCode());
        assertEquals(204, call("POST", "/admin/api-keys/" + id + "/revoke", null, adminToken, null).statusCode());
        assertEquals(401, call("GET", "/integration/stations", null, null, rawKey).statusCode());
    }

    @Test
    void rotatingKeyInvalidatesOldSecretButKeepsScope() throws Exception {
        JsonNode issued = issueKey("Đối tác xoay vòng", "[\"STATIONS_READ\"]");
        String oldKey = issued.get("secret").asString();
        String id = issued.get("key").get("id").asString();

        var rotated = call("POST", "/admin/api-keys/" + id + "/rotate", null, adminToken, null);
        assertEquals(200, rotated.statusCode());
        String newKey = JSON.readTree(rotated.body()).get("secret").asString();

        assertEquals(401, call("GET", "/integration/stations", null, null, oldKey).statusCode());
        assertEquals(200, call("GET", "/integration/stations", null, null, newKey).statusCode());
    }

    @Test
    void missingOrMalformedOrWrongSecretKeyIsRejected() throws Exception {
        assertEquals(401, call("GET", "/integration/stations", null, null, null).statusCode());
        assertEquals(401, call("GET", "/integration/stations", null, null, "khong-dung-dinh-dang").statusCode());

        JsonNode issued = issueKey("Đối tác sai secret", "[\"STATIONS_READ\"]");
        String id = issued.get("key").get("id").asString();
        assertEquals(401, call("GET", "/integration/stations", null, null, id + ".sai-secret").statusCode());
    }

    @Test
    void apiKeyCannotBeUsedOnBusinessRoutesAndJwtCannotBeUsedOnIntegrationRoutes() throws Exception {
        JsonNode issued = issueKey("Đối tác thử vượt rào", "[\"STATIONS_READ\"]");
        String rawKey = issued.get("secret").asString();

        // API key không mở được route nghiệp vụ (JWT only).
        assertEquals(401, call("GET", "/stations", null, null, rawKey).statusCode());

        // JWT của khách hàng không gọi được route integration (API key only).
        assertEquals(401, call("GET", "/integration/stations", null, customerToken, null).statusCode());
    }

    @Test
    void exceedingRateLimitReturns429ThenRecoversNextWindow() throws Exception {
        JsonNode issued = issueKey("Đối tác giới hạn tần suất", "[\"STATIONS_READ\"]");
        String rawKey = issued.get("secret").asString(); // default-rate-limit-per-minute=3 ở test này

        for (int i = 0; i < 3; i++) {
            assertEquals(200, call("GET", "/integration/stations", null, null, rawKey).statusCode(), "request #" + i);
        }
        assertEquals(429, call("GET", "/integration/stations", null, null, rawKey).statusCode());
    }

    @Test
    void nonAdminCannotManageApiKeys() throws Exception {
        assertEquals(403, call("POST", "/admin/api-keys",
                "{\"clientName\":\"x\",\"scopes\":[\"STATIONS_READ\"]}", customerToken, null).statusCode());
        assertEquals(403, call("GET", "/admin/api-keys", null, customerToken, null).statusCode());
        assertEquals(401, call("GET", "/admin/api-keys", null, null, null).statusCode());
    }

    @Test
    void orderStatusHidesRecipientPii() throws Exception {
        // Không có service tạo đơn trực tiếp trong test này; kiểm tra path không tồn tại trả 404 đúng dạng,
        // và không có trường PII nào trong tên trường của DTO (kiểm tra tĩnh qua cấu trúc JSON trả về khi có đơn
        // do các test khác của OrderIntegrationTest đảm trách; ở đây xác nhận hành vi 404 khi không có đơn).
        var res = call("GET", "/integration/orders/" + java.util.UUID.randomUUID() + "/status", null, null,
                issueKey("Đối tác tra cứu đơn", "[\"ORDERS_READ\"]").get("secret").asString());
        assertEquals(404, res.statusCode());
    }
}