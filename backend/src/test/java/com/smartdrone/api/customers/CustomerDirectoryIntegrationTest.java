package com.smartdrone.api.customers;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:customer-directory-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.key=customer-directory-test-signing-key-over-32-characters"
})
@ActiveProfiles("h2")
class CustomerDirectoryIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AccessTokenIssuer tokens;

    private User user(RoleName role, String name) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@customer.test", "test-hash", name,
                roles.findByName(role).orElseThrow()));
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) req.header("Authorization", "Bearer " + token);
        req.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return client.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void dispatcherSeesOnlyCustomersAndCanSearch() throws Exception {
        String dispatcher = tokens.issue(user(RoleName.DISPATCHER, "Điều phối test"));
        User customer = user(RoleName.CUSTOMER, "Khách Tìm Kiếm");
        user(RoleName.LOGISTICS_MANAGER, "Quản lý Tìm Kiếm");
        var response = call("GET", "/customers?search=T%C3%ACm%20Ki%E1%BA%BFm&page=1&pageSize=10", dispatcher, null);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data = JSON.readTree(response.body());
        assertEquals(1, data.get("total").asInt());
        assertEquals(customer.getId().toString(), data.get("items").get(0).get("id").asString());
        assertFalse(response.body().contains("passwordHash"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(400, call("GET", "/customers?page=0", dispatcher, null).statusCode());
    }

    @Test
    void updateOnlyCustomerContactFieldsAndEnforceDispatcherRole() throws Exception {
        User dispatcher = user(RoleName.DISPATCHER, "Điều phối sửa");
        User customer = user(RoleName.CUSTOMER, "Tên cũ");
        String token = tokens.issue(dispatcher);
        String path = "/customers/" + customer.getId();
        String body = "{\"fullName\":\"Tên mới\",\"phone\":\"+84912345678\"}";
        assertEquals(401, call("PATCH", path, null, body).statusCode());
        assertEquals(403, call("PATCH", path, tokens.issue(customer), body).statusCode());
        var updated = call("PATCH", path, token, body);
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals("Tên mới", JSON.readTree(updated.body()).get("fullName").asString());
        User saved = users.findWithRoleById(customer.getId()).orElseThrow();
        assertEquals("Tên mới", saved.getFullName());
        assertEquals("+84912345678", saved.getPhone());
        assertEquals(customer.getEmail(), saved.getEmail());
        assertTrue(saved.isEnabled());
        assertEquals(RoleName.CUSTOMER, saved.getRole().getName());
        assertEquals(400, call("PATCH", path, token,
                "{\"fullName\":\"X\",\"phone\":\"\",\"email\":\"attacker@test.invalid\"}").statusCode());
        assertEquals(400, call("PATCH", path, token, "{\"fullName\":\" \",\"phone\":\"abc\"}").statusCode());
        assertEquals(404, call("PATCH", "/customers/" + dispatcher.getId(), token,
                "{\"fullName\":\"X\",\"phone\":\"\"}").statusCode());
    }
}
