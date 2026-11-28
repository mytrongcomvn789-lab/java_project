package com.smartdrone.api.stations;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.domain.entity.ChargingDevice;
import com.smartdrone.domain.entity.Drone;
import com.smartdrone.domain.entity.StaffStationAssignment;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.DroneStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.ChargingDeviceRepository;
import com.smartdrone.infrastructure.persistence.DroneRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.StaffStationAssignmentRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:charging-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.key=charging-integration-test-signing-key-over-32-characters",
        "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class StationChargingIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired StationRepository stations;
    @Autowired StaffStationAssignmentRepository assignments;
    @Autowired ChargingDeviceRepository devices;
    @Autowired DroneRepository drones;
    @Autowired AccessTokenIssuer tokens;
    @Autowired Clock clock;

    Station station;
    User operator;
    String operatorToken;
    ChargingDevice charging;
    ChargingDevice full;
    ChargingDevice empty;

    @BeforeEach
    void setUp() {
        devices.deleteAll();
        drones.deleteAll();
        assignments.deleteAll();
        station = stations.saveAndFlush(new Station("Trạm sạc test", 10.0, 106.0, 4, "1 Test"));
        operator = newUser(RoleName.STATION_OPERATOR);
        operatorToken = tokens.issue(operator);
        assignments.saveAndFlush(new StaffStationAssignment(operator, station, Instant.now(clock)));

        Instant now = Instant.now(clock);
        var d1 = drones.save(new Drone("DR-T1", 20, DroneStatus.CHARGING, station.getId()));
        var d2 = drones.save(new Drone("DR-T2", 80, DroneStatus.CHARGING, station.getId()));
        drones.save(new Drone("DR-T3", 90, DroneStatus.IDLE, station.getId()));
        charging = new ChargingDevice(station.getId(), "T-01");
        charging.plug(d1.getId(), 20, now.minus(Duration.ofMinutes(30)), 100);
        charging.heartbeat(now);
        full = new ChargingDevice(station.getId(), "T-02");
        full.plug(d2.getId(), 80, now.minus(Duration.ofMinutes(60)), 100);
        full.heartbeat(now);
        empty = new ChargingDevice(station.getId(), "T-03");
        empty.heartbeat(now);
        devices.saveAll(java.util.List.of(charging, full, empty));
    }

    private User newUser(RoleName role) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@charging.test", "unused-test-hash",
                "Charging tester", roles.findByName(role).orElseThrow()));
    }

    private HttpResponse<String> call(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }

    private String base() {
        return "/stations/" + station.getId() + "/chargers";
    }

    @Test
    void operatorSeesChargerDetailAndSummary() throws Exception {
        var r = call("GET", base(), operatorToken, null);
        assertEquals(200, r.statusCode(), r.body());
        var body = json(r);
        assertEquals("SIMULATED", body.get("source").asString());
        assertEquals(3, body.get("summary").get("total").asInt());
        assertEquals(1, body.get("summary").get("charging").asInt());
        assertEquals(1, body.get("summary").get("full").asInt());
        assertEquals(1, body.get("summary").get("empty").asInt());
        assertTrue(body.get("nextSlotAt").isNull(), "còn thiết bị trống nên chưa cần chờ");
        assertEquals(1, body.get("idleDrones").size());

        var first = body.get("chargers").get(0);
        assertEquals("T-01", first.get("code").asString());
        assertEquals("CHARGING", first.get("state").asString());
        assertEquals(50, first.get("batteryPercent").asInt());
        assertEquals("DR-T1", first.get("drone").get("code").asString());
        assertTrue(first.get("online").asBoolean());
        assertFalse(first.get("canRelease").asBoolean());
        assertTrue(body.get("chargers").get(1).get("canRelease").asBoolean());
    }

    @Test
    void otherRolesAndUnassignedOperatorsCannotSeeDetail() throws Exception {
        assertEquals(403, call("GET", base(), tokens.issue(newUser(RoleName.CUSTOMER)), null).statusCode());
        assertEquals(403, call("GET", base(), tokens.issue(newUser(RoleName.LOGISTICS_MANAGER)), null).statusCode());
        assertEquals(403, call("GET", base(), tokens.issue(newUser(RoleName.DISPATCHER)), null).statusCode());
        assertEquals(401, call("GET", base(), null, null).statusCode());
        // Nhân viên trạm chưa được phân công trạm này: 404, không lộ dữ liệu.
        assertEquals(404, call("GET", base(), tokens.issue(newUser(RoleName.STATION_OPERATOR)), null).statusCode());
    }

    @Test
    void releaseOnlyWorksForFullDeviceAndFreesTheDrone() throws Exception {
        var notFull = call("POST", base() + "/" + charging.getId() + "/release", operatorToken, null);
        assertEquals(400, notFull.statusCode(), notFull.body());

        var ok = call("POST", base() + "/" + full.getId() + "/release", operatorToken, null);
        assertEquals(200, ok.statusCode(), ok.body());
        var body = json(ok);
        assertEquals(2, body.get("summary").get("empty").asInt());
        assertEquals(0, body.get("summary").get("full").asInt());
        assertEquals(2, body.get("idleDrones").size(), "drone vừa rời thiết bị chuyển sang đỗ tại trạm");
        assertEquals(DroneStatus.IDLE, drones.findAll().stream()
                .filter(d -> d.getCode().equals("DR-T2")).findFirst().orElseThrow().getStatus());
    }

    @Test
    void maintenanceRequiresEmptyDeviceAndTurnsItRed() throws Exception {
        var busy = call("PATCH", base() + "/" + charging.getId() + "/maintenance", operatorToken,
                java.util.Map.of("maintenance", true));
        assertEquals(400, busy.statusCode(), busy.body());

        var ok = call("PATCH", base() + "/" + empty.getId() + "/maintenance", operatorToken,
                java.util.Map.of("maintenance", true));
        assertEquals(200, ok.statusCode(), ok.body());
        assertEquals(1, json(ok).get("summary").get("maintenance").asInt());

        var back = call("PATCH", base() + "/" + empty.getId() + "/maintenance", operatorToken,
                java.util.Map.of("maintenance", false));
        assertEquals(0, json(back).get("summary").get("maintenance").asInt());
    }

    @Test
    void monitoringShowsChargerIconsAndConnectionFromHeartbeat() throws Exception {
        var r = call("GET", "/stations/monitoring", operatorToken, null);
        assertEquals(200, r.statusCode(), r.body());
        var row = json(r).get(0);
        assertEquals("ONLINE", row.get("connection").get("state").asString());
        assertEquals("CHARGER_HEARTBEAT", row.get("connection").get("source").asString());
        assertEquals(3, row.get("chargers").size());
        assertEquals("CHARGING", row.get("chargers").get(0).get("state").asString());
        assertEquals("FULL", row.get("chargers").get(1).get("state").asString());
        assertEquals("EMPTY", row.get("chargers").get(2).get("state").asString());
        assertFalse(row.get("chargers").get(0).has("drone"), "trang chung không lộ chi tiết drone");
    }
}
