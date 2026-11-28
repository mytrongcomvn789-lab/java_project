package com.smartdrone.api.dispatch;

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
import com.smartdrone.application.dispatch.DroneDispatchService;
import com.smartdrone.domain.entity.ChargingDevice;
import com.smartdrone.domain.entity.Drone;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.entity.RecipientSnapshot;
import com.smartdrone.domain.entity.StaffStationAssignment;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.DroneStatus;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.ChargingDeviceRepository;
import com.smartdrone.infrastructure.persistence.DroneAssignmentRepository;
import com.smartdrone.infrastructure.persistence.DroneRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.StaffStationAssignmentRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:dispatch-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.key=dispatch-integration-test-signing-key-over-32-characters",
        "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class DroneDispatchIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired StationRepository stations;
    @Autowired StaffStationAssignmentRepository staff;
    @Autowired OrderRepository orders;
    @Autowired DroneRepository drones;
    @Autowired ChargingDeviceRepository devices;
    @Autowired DroneAssignmentRepository assignments;
    @Autowired DroneDispatchService dispatch;
    @Autowired AccessTokenIssuer tokens;
    @Autowired Clock clock;

    Station pickup;
    Station dropoff;
    User operator;
    User customer;
    String operatorToken;
    String customerToken;
    Drone local;
    ChargingDevice target;

    @BeforeEach
    void setUp() {
        assignments.deleteAll();
        devices.deleteAll();
        drones.deleteAll();
        orders.deleteAll();
        staff.deleteAll();
        pickup = stations.saveAndFlush(new Station("Trạm gửi", 10.7769, 106.7009, 4, "A"));
        dropoff = stations.saveAndFlush(new Station("Trạm nhận", 10.8012, 106.7141, 4, "B"));
        operator = newUser(RoleName.STATION_OPERATOR);
        customer = newUser(RoleName.CUSTOMER);
        operatorToken = tokens.issue(operator);
        customerToken = tokens.issue(customer);
        staff.saveAndFlush(new StaffStationAssignment(operator, pickup, Instant.now(clock)));
        staff.saveAndFlush(new StaffStationAssignment(operator, dropoff, Instant.now(clock)));

        local = drones.save(new Drone("DR-LOCAL", 90, DroneStatus.IDLE, pickup.getId()));
        target = new ChargingDevice(dropoff.getId(), "B-01");
        target.heartbeat(Instant.now(clock));
        devices.saveAndFlush(target);
    }

    private User newUser(RoleName role) {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@dispatch.test", "unused-test-hash",
                "Dispatch tester", roles.findByName(role).orElseThrow()));
    }

    private Order preparingOrder() {
        var order = new Order(customer.getId(), pickup.getId(), dropoff.getId(),
                new RecipientSnapshot("Người nhận", "0900000000", "1 Lê Lợi", 10.77, 106.70), Instant.now());
        order.changeStatus(OrderStatus.PREPARING);
        return orders.saveAndFlush(order);
    }

    private HttpResponse<String> call(String method, String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) throws Exception {
        return JSON.readTree(r.body());
    }

    @Test
    void previewShowsPlanToStaffAndHidesOperationalDetailFromCustomer() throws Exception {
        var order = preparingOrder();
        var staffView = json(call("GET", "/orders/" + order.getId() + "/dispatch", operatorToken));
        assertEquals("PLANNED", staffView.get("stage").asString());
        assertEquals("DR-LOCAL", staffView.get("droneCode").asString());
        assertEquals("B-01", staffView.get("reservedChargerCode").asString());
        assertEquals("FORMULA", staffView.get("source").asString());

        var customerView = json(call("GET", "/orders/" + order.getId() + "/dispatch", customerToken));
        assertEquals("PLANNED", customerView.get("stage").asString());
        assertTrue(customerView.get("droneCode").isNull());
        assertTrue(customerView.get("reservedChargerCode").isNull());
        assertFalse(customerView.get("message").asString().isBlank());
        assertEquals(0, assignments.count(), "xem trước không được lưu gì");
    }

    @Test
    void assignDispatchAndArriveMoveTheDroneThroughItsLifecycle() throws Exception {
        var order = preparingOrder();
        var assigned = call("POST", "/orders/" + order.getId() + "/dispatch-plan", operatorToken);
        assertEquals(200, assigned.statusCode(), assigned.body());
        assertEquals("ASSIGNED", json(assigned).get("stage").asString());

        // Đơn thứ hai không được dùng lại drone và thiết bị đã đặt: hết drone.
        var second = preparingOrder();
        var secondView = json(call("GET", "/orders/" + second.getId() + "/dispatch", operatorToken));
        assertEquals("NO_DRONE", secondView.get("stage").asString());

        dispatch.onDispatched(orders.findById(order.getId()).orElseThrow());
        var flying = drones.findById(local.getId()).orElseThrow();
        assertEquals(DroneStatus.IN_FLIGHT, flying.getStatus());
        assertNull(flying.getStationId());
        assertEquals("IN_FLIGHT", json(call("GET", "/orders/" + order.getId() + "/dispatch", operatorToken))
                .get("stage").asString());

        dispatch.onArrived(orders.findById(order.getId()).orElseThrow());
        var landed = drones.findById(local.getId()).orElseThrow();
        assertEquals(dropoff.getId(), landed.getStationId());
        assertEquals(DroneStatus.CHARGING, landed.getStatus(), "hạ cánh xuống thiết bị đã đặt trước và cắm sạc");
        var device = devices.findById(target.getId()).orElseThrow();
        assertEquals(local.getId(), device.getDroneId());
        assertTrue(landed.getBatteryPercent() < 90, "chặng bay đã tiêu hao pin");
    }

    @Test
    void assignRequiresOperatorOfPickupStationAndPreparingOrder() throws Exception {
        var order = preparingOrder();
        assertEquals(403, call("POST", "/orders/" + order.getId() + "/dispatch-plan", customerToken).statusCode());
        var outsider = tokens.issue(newUser(RoleName.STATION_OPERATOR));
        assertEquals(404, call("POST", "/orders/" + order.getId() + "/dispatch-plan", outsider).statusCode());

        order.changeStatus(OrderStatus.SCHEDULED);
        orders.saveAndFlush(order);
        assertEquals(400, call("POST", "/orders/" + order.getId() + "/dispatch-plan", operatorToken).statusCode());
    }

    @Test
    void boardListsFleetAndOrdersInScope() throws Exception {
        var order = preparingOrder();
        var board = json(call("GET", "/dispatch/board", operatorToken));
        assertEquals(1, board.get("fleet").size());
        assertEquals("DR-LOCAL", board.get("fleet").get(0).get("code").asString());
        assertEquals(1, board.get("orders").size());
        assertEquals(order.getId().toString(), board.get("orders").get(0).get("orderId").asString());
        assertEquals(403, call("GET", "/dispatch/board", customerToken).statusCode());
    }

    @Test
    void sweepAssignsWaitingOrdersAndCancelsPlansOfOrdersNoLongerWaiting() throws Exception {
        var order = preparingOrder();
        dispatch.sweep();
        assertEquals(1, assignments.count());
        assertEquals("ASSIGNED", assignments.findAll().get(0).getStatus().name());

        order.changeStatus(OrderStatus.FAILED);
        orders.saveAndFlush(order);
        dispatch.sweep();
        assertEquals("CANCELLED", assignments.findAll().get(0).getStatus().name());
    }
}
