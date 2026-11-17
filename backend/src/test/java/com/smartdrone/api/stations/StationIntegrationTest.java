package com.smartdrone.api.stations;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.stations.StationDirectory;
import com.smartdrone.domain.entity.StaffStationAssignment;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.domain.enums.StationStatus;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.StaffStationAssignmentRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;
import com.smartdrone.infrastructure.persistence.StationStatusEventRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:station-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.key=apddmp32-station-test-signing-key-over-32-characters",
        "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class StationIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired StationRepository stations;
    @Autowired AccessTokenIssuer tokens;
    @Autowired StationDirectory stationDirectory;
    @Autowired StaffStationAssignmentRepository assignments;
    @Autowired StationStatusEventRepository events;

    record Actor(String token) { }

    record Identity(User user, Actor actor) { }

    @BeforeEach
    void clearStations() {
        assignments.deleteAll();
        events.deleteAll();
        stations.deleteAll();
    }

    private Actor actor(RoleName role) {
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@station.test",
                "unused-test-hash", "Station tester", roles.findByName(role).orElseThrow()));
        return new Actor(tokens.issue(user));
    }

    private Identity identity(RoleName role) {
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@station.test",
                "unused-test-hash", "Station tester", roles.findByName(role).orElseThrow()));
        return new Identity(user, new Actor(tokens.issue(user)));
    }

    private Map<String, Object> valid() {
        return new LinkedHashMap<>(Map.of(
                "name", "Trạm trung tâm",
                "latitude", 10.7769,
                "longitude", 106.7009,
                "capacity", 4));
    }

    private HttpResponse<String> call(String method, String path, Actor actor, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (actor != null) request.header("Authorization", "Bearer " + actor.token());
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body());
    }

    private UUID create(Actor manager) throws Exception {
        var response = call("POST", "/stations", manager, valid());
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(json(response).get("id").asString());
    }

    @Test
    void managerCanCreateListUpdateAndDeactivateStationWithoutDeletingIt() throws Exception {
        var manager = actor(RoleName.LOGISTICS_MANAGER);
        var input = valid();
        input.put("name", "  Trạm trung tâm  ");
        var created = call("POST", "/stations", manager, input);
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("Trạm trung tâm", json(created).get("name").asString());
        assertEquals(4, json(created).get("capacity").asInt());
        assertEquals("ACTIVE", json(created).get("status").asString());
        String id = json(created).get("id").asString();
        assertTrue(created.headers().firstValue("Location").orElseThrow().endsWith("/stations/" + id));
        assertEquals("no-store", created.headers().firstValue("Cache-Control").orElseThrow());

        var listed = call("GET", "/stations", manager, null);
        assertEquals(200, listed.statusCode(), listed.body());
        assertEquals(1, json(listed).size());
        assertEquals(id, json(listed).get(0).get("id").asString());

        input.put("name", "Trạm cập nhật");
        input.put("capacity", 8);
        var updated = call("PUT", "/stations/" + id, manager, input);
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals("Trạm cập nhật", json(updated).get("name").asString());
        assertEquals(8, json(updated).get("capacity").asInt());

        var inactive = call("PATCH", "/stations/" + id + "/status", manager, Map.of("status", "INACTIVE"));
        assertEquals(200, inactive.statusCode(), inactive.body());
        assertEquals("INACTIVE", json(inactive).get("status").asString());
        assertFalse(stationDirectory.isActive(UUID.fromString(id)));
        assertTrue(stations.existsById(UUID.fromString(id)));
    }

    @ParameterizedTest
    @EnumSource(RoleName.class)
    void everyAuthenticatedRoleCanReadStations(RoleName role) throws Exception {
        var actor = actor(role);
        assertEquals(200, call("GET", "/stations", actor, null).statusCode());
    }

    @Test
    void invalidCoordinatesCapacityOrNameAreRejectedWithFieldErrors() throws Exception {
        var manager = actor(RoleName.LOGISTICS_MANAGER);
        var input = valid();
        input.put("latitude", 90.01);
        input.put("capacity", 0);
        var response = call("POST", "/stations", manager, input);
        assertEquals(400, response.statusCode(), response.body());
        assertEquals("VALIDATION_ERROR", json(response).get("code").asString());
        assertEquals(0, stations.count());

        input = valid();
        input.put("longitude", -180.01);
        input.put("name", "  ");
        response = call("POST", "/stations", manager, input);
        assertEquals(400, response.statusCode(), response.body());
        assertEquals(0, stations.count());
    }

    @ParameterizedTest
    @EnumSource(value = RoleName.class, names = "LOGISTICS_MANAGER", mode = EnumSource.Mode.EXCLUDE)
    void nonManagersCannotWriteAndUnassignedOperatorsCannotChangeStatus(RoleName role) throws Exception {
        var caller = actor(role);
        var manager = actor(RoleName.LOGISTICS_MANAGER);
        UUID stationId = create(manager);

        assertEquals(403, call("POST", "/stations", caller, valid()).statusCode());
        assertEquals(403, call("PUT", "/stations/" + stationId, caller, valid()).statusCode());
        if (role == RoleName.STATION_OPERATOR) {
            var operatorStatus = call("PATCH", "/stations/" + stationId + "/status",
                    caller, Map.of("status", "INACTIVE"));
            assertEquals(403, operatorStatus.statusCode(), operatorStatus.body());
        } else {
            assertEquals(403, call("PATCH", "/stations/" + stationId + "/status",
                    caller, Map.of("status", "INACTIVE")).statusCode());
        }
        assertEquals("ACTIVE", stations.findById(stationId).orElseThrow().getStatus().name());
    }

    @Test
    void assignedOperatorChangesOnlyAssignedStationAndEveryChangeIsAudited() throws Exception {
        var manager = actor(RoleName.LOGISTICS_MANAGER);
        UUID assignedId = create(manager);
        UUID otherId = create(manager);
        var operator = identity(RoleName.STATION_OPERATOR);
        assignments.saveAndFlush(new StaffStationAssignment(
                operator.user(), stations.findById(assignedId).orElseThrow(), Instant.now()));

        var maintenance = call("PATCH", "/stations/" + assignedId + "/status",
                operator.actor(), Map.of("status", "MAINTENANCE"));
        assertEquals(200, maintenance.statusCode(), maintenance.body());
        assertEquals("MAINTENANCE", json(maintenance).get("status").asString());
        assertFalse(stationDirectory.isActive(assignedId));
        assertTrue(stations.existsById(assignedId));

        var foreign = call("PATCH", "/stations/" + otherId + "/status",
                operator.actor(), Map.of("status", "MAINTENANCE"));
        assertEquals(403, foreign.statusCode(), foreign.body());
        assertEquals(StationStatus.ACTIVE, stations.findById(otherId).orElseThrow().getStatus());

        var recorded = events.findByStationIdOrderByOccurredAtAsc(assignedId);
        assertEquals(1, recorded.size());
        assertEquals(StationStatus.ACTIVE, recorded.get(0).getFromStatus());
        assertEquals(StationStatus.MAINTENANCE, recorded.get(0).getToStatus());
        assertEquals(operator.user().getId(), recorded.get(0).getActorId());
        assertEquals(RoleName.STATION_OPERATOR, recorded.get(0).getActorRole());
        assertTrue(events.findByStationIdOrderByOccurredAtAsc(otherId).isEmpty());
    }

    @Test
    void sameStatusIsNotAuditedAndMaintenanceStationCanBeReactivated() throws Exception {
        var manager = actor(RoleName.LOGISTICS_MANAGER);
        UUID id = create(manager);
        var path = "/stations/" + id + "/status";

        assertEquals(200, call("PATCH", path, manager, Map.of("status", "MAINTENANCE")).statusCode());
        assertEquals(200, call("PATCH", path, manager, Map.of("status", "MAINTENANCE")).statusCode());
        assertEquals(1, events.findByStationIdOrderByOccurredAtAsc(id).size());
        assertFalse(stationDirectory.isActive(id));

        var reactivated = call("PATCH", path, manager, Map.of("status", "ACTIVE"));
        assertEquals(200, reactivated.statusCode(), reactivated.body());
        assertTrue(stationDirectory.isActive(id));
        assertEquals(2, events.findByStationIdOrderByOccurredAtAsc(id).size());
    }

    @Test
    void operatorGetsNotFoundForUnknownStationAndInvalidStatusIsRejected() throws Exception {
        var operator = identity(RoleName.STATION_OPERATOR);
        var unknown = call("PATCH", "/stations/" + UUID.randomUUID() + "/status",
                operator.actor(), Map.of("status", "MAINTENANCE"));
        assertEquals(404, unknown.statusCode(), unknown.body());

        var manager = actor(RoleName.LOGISTICS_MANAGER);
        UUID id = create(manager);
        var invalid = call("PATCH", "/stations/" + id + "/status", manager, Map.of("status", "BROKEN"));
        assertEquals(400, invalid.statusCode(), invalid.body());
        assertEquals(StationStatus.ACTIVE, stations.findById(id).orElseThrow().getStatus());
    }

    @Test
    void anonymousCannotReadStations() throws Exception {
        var response = call("GET", "/stations", null, null);
        assertEquals(401, response.statusCode(), response.body());
        assertEquals("AUTH_REQUIRED", json(response).get("code").asString());
    }
}
