package com.smartdrone.api.addresses;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import com.smartdrone.application.addresses.AddressService;
import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.*;
import tools.jackson.databind.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${ADDRESS_TEST_DATABASE_URL:jdbc:h2:mem:address-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1}",
    "spring.datasource.driver-class-name=${ADDRESS_TEST_DATABASE_DRIVER:org.h2.Driver}",
    "spring.datasource.username=${ADDRESS_TEST_DATABASE_USER:sa}",
    "spring.datasource.password=${ADDRESS_TEST_DATABASE_PASSWORD:}",
    "app.jwt.key=apddmp22-test-signing-key-longer-than-32-characters",
    "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class AddressIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AddressRepository addresses;
    @Autowired AccessTokenIssuer tokens;
    @Autowired AddressService service;

    record Actor(UUID id, String token) { }
    private Actor actor(RoleName role) {
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@address.test", "unused-test-hash",
                "Address tester", roles.findByName(role).orElseThrow()));
        return new Actor(user.getId(), tokens.issue(user));
    }
    private Map<String, Object> valid() {
        return new LinkedHashMap<>(Map.of("label", "Nhà riêng", "recipientName", "Nguyễn An",
                "phone", "0901234567", "addressLine", "10 Nguyễn Huệ, TP.HCM",
                "latitude", 10.7769, "longitude", 106.7009));
    }
    private HttpResponse<String> call(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response) { return JSON.readTree(response.body()); }
    private UUID create(Actor actor) throws Exception {
        var response = call("POST", "/addresses", actor.token(), valid());
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(json(response).get("id").asString());
    }

    @Test
    void ownerCanCreateListUpdateDeleteAndArchivedRowsStayInDatabase() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        var input = valid(); input.put("label", "  Nhà riêng  ");
        var response = call("POST", "/addresses", a.token(), input);
        assertEquals(201, response.statusCode(), response.body());
        String id = json(response).get("id").asString();
        assertTrue(response.headers().firstValue("Location").orElseThrow().endsWith("/api/v1/addresses/" + id));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("Nhà riêng", json(response).get("label").asString());
        assertFalse(json(response).has("owner")); assertFalse(json(response).has("userId"));
        assertEquals(7, json(response).size());

        var listed = call("GET", "/addresses", a.token(), null);
        assertEquals(200, listed.statusCode(), listed.body());
        assertEquals(1, json(listed).get("total").asInt());
        assertEquals(1, json(listed).get("page").asInt());
        assertEquals(20, json(listed).get("pageSize").asInt());
        input.put("label", "Văn phòng"); input.put("latitude", 0); input.put("longitude", 0);
        var updated = call("PUT", "/addresses/" + id, a.token(), input);
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals("Văn phòng", json(updated).get("label").asString());

        assertEquals(204, call("DELETE", "/addresses/" + id, a.token(), null).statusCode());
        assertTrue(addresses.existsById(UUID.fromString(id)));
        assertEquals(0, json(call("GET", "/addresses", a.token(), null)).get("total").asInt());
        assertEquals(404, call("PUT", "/addresses/" + id, a.token(), input).statusCode());
        assertEquals(404, call("DELETE", "/addresses/" + id, a.token(), null).statusCode());
    }

    @Test
    void anotherCustomerCannotListUpdateOrDeleteOwnersAddress() throws Exception {
        var a = actor(RoleName.CUSTOMER); var b = actor(RoleName.CUSTOMER);
        UUID id = create(a);
        assertEquals(0, json(call("GET", "/addresses", b.token(), null)).get("total").asInt());
        for (String method : List.of("PUT", "DELETE")) {
            var response = call(method, "/addresses/" + id, b.token(), method.equals("PUT") ? valid() : null);
            assertEquals(404, response.statusCode(), response.body());
            assertEquals("RESOURCE_NOT_FOUND", json(response).get("code").asString());
        }
        assertEquals(1, json(call("GET", "/addresses", a.token(), null)).get("total").asInt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"customerId", "userId", "owner", "id", "deleted", "unexpected"})
    void unknownFieldsCannotForgeOwnershipOrInternalState(String field) throws Exception {
        var a = actor(RoleName.CUSTOMER); var input = valid(); input.put(field, UUID.randomUUID().toString());
        var response = call("POST", "/addresses", a.token(), input);
        assertEquals(400, response.statusCode(), response.body());
        assertEquals(0, json(call("GET", "/addresses", a.token(), null)).get("total").asInt());
        UUID id = create(a);
        assertEquals(400, call("PUT", "/addresses/" + id, a.token(), input).statusCode());
    }

    static Stream<Arguments> invalidFields() {
        return Stream.of(Arguments.of("label", "  "), Arguments.of("recipientName", ""),
                Arguments.of("addressLine", "a".repeat(501)), Arguments.of("label", "a".repeat(501)),
                Arguments.of("recipientName", "a".repeat(501)), Arguments.of("phone", "abc"),
                Arguments.of("phone", "123"), Arguments.of("phone", "+1234567890123456"),
                Arguments.of("latitude", 91), Arguments.of("latitude", -91),
                Arguments.of("longitude", 181), Arguments.of("longitude", -181),
                Arguments.of("latitude", null), Arguments.of("longitude", null),
                Arguments.of("phone", null), Arguments.of("addressLine", null));
    }
    @ParameterizedTest @MethodSource("invalidFields")
    void invalidInputReturns400WithoutCreatingData(String field, Object value) throws Exception {
        var a = actor(RoleName.CUSTOMER); var input = valid(); input.put(field, value);
        var response = call("POST", "/addresses", a.token(), input);
        assertEquals(400, response.statusCode(), response.body());
        assertEquals("VALIDATION_ERROR", json(response).get("code").asString());
        assertEquals(0, json(call("GET", "/addresses", a.token(), null)).get("total").asInt());
    }

    @ParameterizedTest
    @EnumSource(value = RoleName.class, names = "CUSTOMER", mode = EnumSource.Mode.EXCLUDE)
    void fourOtherRolesAreDeniedOnAllAddressOperations(RoleName role) throws Exception {
        var a = actor(role);
        for (String method : List.of("GET", "POST", "PUT", "DELETE")) {
            String path = "/addresses" + (List.of("PUT", "DELETE").contains(method) ? "/" + UUID.randomUUID() : "");
            var response = call(method, path, a.token(), List.of("POST", "PUT").contains(method) ? valid() : null);
            assertEquals(403, response.statusCode(), method + ": " + response.body());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"GET", "POST", "PUT", "DELETE"})
    void anonymousRequestsAreDenied(String method) throws Exception {
        String path = "/addresses" + (List.of("PUT", "DELETE").contains(method) ? "/" + UUID.randomUUID() : "");
        assertEquals(401, call(method, path, null, List.of("POST", "PUT").contains(method) ? valid() : null).statusCode());
    }

    @ParameterizedTest @ValueSource(strings = {"page=0", "page=-1", "pageSize=0", "pageSize=101", "page=abc"})
    void invalidPaginationReturns400(String query) throws Exception {
        var a = actor(RoleName.CUSTOMER);
        assertEquals(400, call("GET", "/addresses?" + query, a.token(), null).statusCode());
    }

    @Test
    void paginationIsStableAndTotalIsScoped() throws Exception {
        var a = actor(RoleName.CUSTOMER); var b = actor(RoleName.CUSTOMER);
        var first = create(a); var second = create(a); var third = create(a); create(b);
        var p1 = json(call("GET", "/addresses?page=1&pageSize=2", a.token(), null));
        var p2 = json(call("GET", "/addresses?page=2&pageSize=2", a.token(), null));
        assertEquals(3, p1.get("total").asInt()); assertEquals(2, p1.get("items").size());
        assertEquals(third.toString(), p1.get("items").get(0).get("id").asString());
        assertEquals(second.toString(), p1.get("items").get(1).get("id").asString());
        assertEquals(first.toString(), p2.get("items").get(0).get("id").asString());
        assertEquals(0, json(call("GET", "/addresses?page=3&pageSize=2", a.token(), null)).get("items").size());
    }

    @Test @WithMockUser(roles = "CUSTOMER")
    void snapshotRemainsUnchangedAfterAddressEditAndDelete() throws Exception {
        var a = actor(RoleName.CUSTOMER); var b = actor(RoleName.CUSTOMER); UUID id = create(a);
        var snapshot = service.snapshotForOrder(a.id(), id);
        var changed = valid(); changed.put("addressLine", "Địa chỉ mới");
        assertEquals(200, call("PUT", "/addresses/" + id, a.token(), changed).statusCode());
        assertEquals("10 Nguyễn Huệ, TP.HCM", snapshot.addressLine());
        assertThrows(ApiException.class, () -> service.snapshotForOrder(b.id(), id));
        assertEquals(204, call("DELETE", "/addresses/" + id, a.token(), null).statusCode());
        assertEquals("10 Nguyễn Huệ, TP.HCM", snapshot.addressLine());
        assertThrows(ApiException.class, () -> service.snapshotForOrder(a.id(), id));
    }

    @Test @WithMockUser(roles = "DISPATCHER")
    void serviceAlsoEnforcesCustomerRole() {
        assertThrows(AccessDeniedException.class, () -> service.list(UUID.randomUUID(), 1, 20));
    }
}
