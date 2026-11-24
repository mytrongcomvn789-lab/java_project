package com.smartdrone.api.stations;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import com.smartdrone.application.scheduling.ScheduleProperties;
import com.smartdrone.domain.entity.DeliveryAttempt;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.entity.OrderPackage;
import com.smartdrone.domain.entity.RecipientSnapshot;
import com.smartdrone.domain.entity.StaffStationAssignment;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.OrderPackageRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.StaffStationAssignmentRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;
import com.smartdrone.infrastructure.persistence.StationStatusEventRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:monitoring-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.key=apddmp46-monitoring-test-signing-key-over-32-characters",
        "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class StationMonitoringIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired StationRepository stations;
    @Autowired OrderRepository orders;
    @Autowired OrderPackageRepository packages;
    @Autowired StaffStationAssignmentRepository assignments;
    @Autowired StationStatusEventRepository events;
    @Autowired AccessTokenIssuer tokens;
    @Autowired DeliveryAttemptRepository attempts;
    @Autowired ScheduleProperties schedule;
    @Autowired Clock clock;

    record Actor(String token) { }

    record Identity(User user, Actor actor) { }

    @BeforeEach
    void clear() {
        // xóa theo thứ tự con trước, cha sau
        packages.deleteAll();
        orders.deleteAll();
        assignments.deleteAll();
        events.deleteAll();
        stations.deleteAll();
        attempts.deleteAll();
    }

    private Identity identity(RoleName role) {
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@monitoring.test",
                "unused-test-hash", "Monitoring tester", roles.findByName(role).orElseThrow()));
        return new Identity(user, new Actor(tokens.issue(user)));
    }

    private Actor actor(RoleName role) {
        return identity(role).actor();
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

    private UUID createStation(Actor manager) throws Exception {
        var body = new LinkedHashMap<String, Object>(Map.of(
                "name", "Trạm giám sát", "address", "45 Lê Lợi, Quận 1, TP. Hồ Chí Minh", "capacity", 6));
        var response = call("POST", "/stations", manager, body);
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(json(response).get("id").asString());
    }

    /** Tạo một đơn có đúng một kiện ở trạng thái cho trước (giống forceStatus trong OrderIntegrationTest). */
    private void seedOrder(UUID customerId, UUID pickup, UUID dropoff, OrderStatus status) {
        var order = new Order(customerId, pickup, dropoff,
                new RecipientSnapshot("Người nhận", "0900000000", "1 Lê Lợi", 10.77, 106.70), Instant.now());
        order.changeStatus(status);
        var saved = orders.saveAndFlush(order);
        var pack = new OrderPackage(saved.getId(), Instant.now());
        pack.update("Gói mẫu", 500, 10, 10, 10, List.of(), Instant.now());
        packages.saveAndFlush(pack);
    }

    private JsonNode row(JsonNode list, UUID id) {
        for (JsonNode node : list) {
            if (id.toString().equals(node.get("id").asString())) return node;
        }
        return fail("Không thấy trạm " + id + " trong kết quả: " + list);
    }

    @Test
    void managerSeesPackageCountsPerGroupAndOtherStatusesAreIgnored() throws Exception {
        var manager = actor(RoleName.LOGISTICS_MANAGER);
        UUID a = createStation(manager);
        UUID b = createStation(manager);
        UUID customer = identity(RoleName.CUSTOMER).user().getId();

        // Đơn đi từ A tới B, mỗi đơn một kiện
        seedOrder(customer, a, b, OrderStatus.SCHEDULED);       // đã lên lịch, kiện chưa tới A
        seedOrder(customer, a, b, OrderStatus.PREPARING);       // kiện đang ở A, chờ xuất
        seedOrder(customer, a, b, OrderStatus.IN_TRANSIT);      // đang tới B
        seedOrder(customer, a, b, OrderStatus.AWAITING_PICKUP); // kiện đang ở B, chờ khách
        seedOrder(customer, a, b, OrderStatus.DELIVERED);       // không tính
        seedOrder(customer, a, b, OrderStatus.PENDING);         // không tính

        var response = call("GET", "/stations/monitoring", manager, null);
        assertEquals(200, response.statusCode(), response.body());
        var list = json(response);
        assertEquals(2, list.size());

        var rowA = row(list, a).get("packages");
        assertEquals(1, rowA.get("scheduled").asLong());
        assertEquals(1, rowA.get("preparing").asLong());
        assertEquals(0, rowA.get("inbound").asLong());
        assertEquals(0, rowA.get("waitingPickup").asLong());
        assertEquals(1, rowA.get("atStation").asLong());
        assertEquals(2, rowA.get("processingTotal").asLong());

        var rowB = row(list, b).get("packages");
        assertEquals(0, rowB.get("scheduled").asLong());
        assertEquals(0, rowB.get("preparing").asLong());
        assertEquals(1, rowB.get("inbound").asLong());
        assertEquals(1, rowB.get("waitingPickup").asLong());
        assertEquals(1, rowB.get("atStation").asLong());
        assertEquals(2, rowB.get("processingTotal").asLong());
    }

    @Test
    void connectionHasNoDataAndMaintenanceIsSeparateFromConnection() throws Exception {
        var manager = actor(RoleName.LOGISTICS_MANAGER);
        UUID active = createStation(manager);
        UUID maintenance = createStation(manager);
        var changed = call("PATCH", "/stations/" + maintenance + "/status", manager, Map.of("status", "MAINTENANCE"));
        assertEquals(200, changed.statusCode(), changed.body());

        var list = json(call("GET", "/stations/monitoring", manager, null));
        var maintenanceRow = row(list, maintenance);
        assertEquals("MAINTENANCE", maintenanceRow.get("status").asString());
        assertEquals("UNKNOWN", maintenanceRow.get("connection").get("state").asString());
        assertTrue(maintenanceRow.get("connection").get("lastSeenAt").isNull());
        assertEquals("ACTIVE", row(list, active).get("status").asString());
        assertEquals(6, maintenanceRow.get("capacity").asInt());

        var onlyMaintenance = json(call("GET", "/stations/monitoring?status=MAINTENANCE", manager, null));
        assertEquals(1, onlyMaintenance.size());
        assertEquals(maintenance.toString(), onlyMaintenance.get(0).get("id").asString());
    }

    @Test
    void operatorSeesOnlyAssignedStationsAndUnassignedStaffSeesNone() throws Exception {
        var manager = actor(RoleName.LOGISTICS_MANAGER);
        UUID mine = createStation(manager);
        createStation(manager); // trạm không được giao
        var operator = identity(RoleName.STATION_OPERATOR);
        assignments.saveAndFlush(new StaffStationAssignment(
                operator.user(), stations.findById(mine).orElseThrow(), Instant.now()));

        var response = call("GET", "/stations/monitoring", operator.actor(), null);
        assertEquals(200, response.statusCode(), response.body());
        var list = json(response);
        assertEquals(1, list.size());
        assertEquals(mine.toString(), list.get(0).get("id").asString());

        var dispatcher = call("GET", "/stations/monitoring", actor(RoleName.DISPATCHER), null);
        assertEquals(200, dispatcher.statusCode(), dispatcher.body());
        assertEquals(0, json(dispatcher).size());
    }

    @Test
    void customerIsForbiddenAndAnonymousIsUnauthorized() throws Exception {
        var customer = call("GET", "/stations/monitoring", actor(RoleName.CUSTOMER), null);
        assertEquals(403, customer.statusCode(), customer.body());
        var anonymous = call("GET", "/stations/monitoring", null, null);
        assertEquals(401, anonymous.statusCode(), anonymous.body());
    }
        @Test
    void slotShowsHeldCapacityFromSchedulingInCurrentWindow() throws Exception {
        var manager = actor(RoleName.LOGISTICS_MANAGER);
        UUID a = createStation(manager);
        UUID b = createStation(manager);
        UUID customer = identity(RoleName.CUSTOMER).user().getId();

        long step = schedule.slotLength().toSeconds();
        Instant start = Instant.ofEpochSecond(Math.floorDiv(Instant.now(clock).getEpochSecond(), step) * step);
        // Một lịch ACTIVE giữ 1 chỗ ở cả trạm gửi A lẫn trạm nhận B trong khung giờ hiện tại
        attempts.saveAndFlush(new DeliveryAttempt(UUID.randomUUID(), 1, a, b,
                start, start.plus(schedule.slotLength()), customer, Instant.now()));

        var list = json(call("GET", "/stations/monitoring", manager, null));
        var slotA = row(list, a).get("slot");
        assertEquals(1, slotA.get("used").asLong());
        assertEquals(5, slotA.get("free").asLong()); // sức chứa 6
        assertEquals(1, row(list, b).get("slot").get("used").asLong());
    }
}