package com.smartdrone.api.orders;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.orders.OrderReviewedEvent;
import com.smartdrone.application.workflow.*;
import com.smartdrone.domain.entity.*;
import com.smartdrone.domain.enums.*;
import com.smartdrone.infrastructure.persistence.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 4.4: DISPATCH (trạm gửi), ARRIVE (trạm nhận) và tracking/timeline qua HTTP thật trên H2. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:tracking44;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
    "app.jwt.key=tracking44-test-signing-key-longer-than-32-characters",
    "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
@Import(OrderTrackingIntegrationTest.Capture.class)
class OrderTrackingIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @TestConfiguration
    static class Capture {
        static final List<OrderReviewedEvent> EVENTS = new CopyOnWriteArrayList<>();
        @Bean Listener progressListener() { return new Listener(); }
    }
    static class Listener {
        @EventListener public void on(OrderReviewedEvent e) { Capture.EVENTS.add(e); }
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired StationRepository stations;
    @Autowired StaffStationAssignmentRepository assignments;
    @Autowired OrderRepository orders;
    @Autowired DeliveryAttemptRepository attempts;
    @Autowired OrderStatusHistoryRepository history;
    @Autowired AccessTokenIssuer tokens;
    @Autowired OrderTransitionService transitions;
    @Autowired PlatformTransactionManager txManager;

    record Actor(User user, String token) { }
    Actor customer, otherCustomer, dispatcher, pickupOp, dropoffOp, outsider;
    Station pickup, dropoff;
    UUID orderId, attemptId, pickupAssignmentId;

    private Actor actor(RoleName role) {
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@tracking.test", "unused",
                "Tracking QA", roles.findByName(role).orElseThrow()));
        return new Actor(user, tokens.issue(user));
    }
    private UUID assign(Actor a, Station s) {
        return assignments.saveAndFlush(new StaffStationAssignment(a.user(), s, Instant.now())).getId();
    }

    @BeforeEach void setup() {
        Capture.EVENTS.clear();
        customer = actor(RoleName.CUSTOMER); otherCustomer = actor(RoleName.CUSTOMER);
        dispatcher = actor(RoleName.DISPATCHER); pickupOp = actor(RoleName.STATION_OPERATOR);
        dropoffOp = actor(RoleName.STATION_OPERATOR); outsider = actor(RoleName.STATION_OPERATOR);
        pickup = stations.saveAndFlush(new Station("Pickup tracking QA", 10.7769, 106.7009, 10));
        dropoff = stations.saveAndFlush(new Station("Dropoff tracking QA", 10.8505, 106.7717, 10));
        pickupAssignmentId = assign(pickupOp, pickup); assign(dropoffOp, dropoff); assign(dispatcher, pickup);
        orderId = orders.saveAndFlush(new Order(customer.user().getId(), pickup.getId(), dropoff.getId(),
                new RecipientSnapshot("Người nhận QA", "0901234567", "Địa chỉ QA", 10.8, 106.7), Instant.now())).getId();
        // Dựng đơn tới PREPARING bằng chính state machine (không có endpoint giả).
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            var o = orders.findById(orderId).orElseThrow();
            transitions.recordCreation(o, customer.user().getId(), RoleName.CUSTOMER);
            transitions.transition(o, new TransitionCommand(OrderAction.APPROVE, dispatcher.user().getId(),
                    RoleName.DISPATCHER, 0, null, null, null));
            attemptId = attempts.saveAndFlush(new DeliveryAttempt(orderId, 1, pickup.getId(), dropoff.getId(),
                    Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
                    dispatcher.user().getId(), Instant.now())).getId();
            transitions.transition(o, new TransitionCommand(OrderAction.SCHEDULE, dispatcher.user().getId(),
                    RoleName.DISPATCHER, 1, null, attemptId, null));
            transitions.transition(o, new TransitionCommand(OrderAction.RECEIVE, pickupOp.user().getId(),
                    RoleName.STATION_OPERATOR, 2, "Ghi chú nội bộ trạm gửi", attemptId, null));
        });
    }

    private HttpResponse<String> call(String method, String path, String token, Object body, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> r) { return JSON.readTree(r.body()); }
    private HttpResponse<String> step(String action, Actor a, long version) throws Exception {
        return call("POST", "/orders/" + orderId + "/" + action, a.token(), Map.of("note", "  Drone DR-01  "),
                "If-Match", "\"" + version + "\"");
    }
    private HttpResponse<String> tracking(Actor a) throws Exception {
        return call("GET", "/orders/" + orderId + "/tracking", a.token(), null);
    }
    private long count(OrderAction action) {
        return history.findByOrderIdOrderByOrderVersionAsc(orderId).stream().filter(h -> h.getAction() == action).count();
    }

    @Test void dispatchAtPickupRecordsActorAttemptTimeAndEvent() throws Exception {
        var before = Instant.now().minusSeconds(1);
        var r = step("dispatch", pickupOp, 3);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("IN_TRANSIT", json(r).get("status").asString());
        assertEquals("\"4\"", r.headers().firstValue("ETag").orElseThrow());
        var e = history.findByOrderIdOrderByOrderVersionAsc(orderId).getLast();
        assertEquals(OrderAction.DISPATCH, e.getAction());
        assertEquals(pickupOp.user().getId(), e.getActorId());
        assertEquals(attemptId, e.getAttemptId());
        assertEquals("Drone DR-01", e.getReason());
        assertTrue(e.getOccurredAt().isAfter(before));
        assertEquals(1, Capture.EVENTS.size());
        assertEquals(e.getId(), Capture.EVENTS.getFirst().eventId());
        assertEquals(OrderAction.DISPATCH, Capture.EVENTS.getFirst().action());
    }

    @Test void dispatchWrongStationOrRoleChangesNothing() throws Exception {
        assertEquals(404, step("dispatch", dropoffOp, 3).statusCode()); // đầu đến không được xuất phát
        assertEquals(404, step("dispatch", outsider, 3).statusCode());
        assertEquals(403, step("dispatch", customer, 3).statusCode());
        assertEquals(403, step("dispatch", dispatcher, 3).statusCode());
        assertEquals(401, call("POST", "/orders/" + orderId + "/dispatch", null, null, "If-Match", "\"3\"").statusCode());
        assertEquals(0, count(OrderAction.DISPATCH));
        assertEquals(OrderStatus.PREPARING, orders.findById(orderId).orElseThrow().getStatus());
        assertTrue(Capture.EVENTS.isEmpty());
    }

    @Test void revokedAssignmentIsCheckedAgainOnWrite() throws Exception {
        assignments.deleteById(pickupAssignmentId);
        assertEquals(404, step("dispatch", pickupOp, 3).statusCode());
        assertEquals(0, count(OrderAction.DISPATCH));
    }

    @Test void invalidRequestsAreRejected() throws Exception {
        assertEquals(412, step("dispatch", pickupOp, 2).statusCode());
        assertEquals(428, call("POST", "/orders/" + orderId + "/dispatch", pickupOp.token(), null).statusCode());
        assertEquals(400, call("POST", "/orders/" + orderId + "/dispatch", pickupOp.token(),
                Map.of("actorId", UUID.randomUUID()), "If-Match", "\"3\"").statusCode());
        assertEquals(400, call("POST", "/orders/" + orderId + "/dispatch", pickupOp.token(),
                Map.of("note", "x".repeat(501)), "If-Match", "\"3\"").statusCode());
        assertEquals(0, count(OrderAction.DISPATCH));
    }

    @Test void releasedAttemptCannotDispatch() throws Exception {
        var a = attempts.findById(attemptId).orElseThrow(); a.release(Instant.now()); attempts.saveAndFlush(a);
        assertEquals(409, step("dispatch", pickupOp, 3).statusCode());
        assertEquals(0, count(OrderAction.DISPATCH));
    }

    @Test void repeatedDispatchWritesOneHistoryRowAndOneEvent() throws Exception {
        assertEquals(200, step("dispatch", pickupOp, 3).statusCode());
        assertEquals(412, step("dispatch", pickupOp, 3).statusCode());
        assertEquals(409, step("dispatch", pickupOp, 4).statusCode());
        assertEquals(1, count(OrderAction.DISPATCH));
        assertEquals(1, Capture.EVENTS.size());
    }

    @Test void arriveNeedsInTransitAndDropoffOperator() throws Exception {
        assertEquals(409, step("arrive", dropoffOp, 3).statusCode()); // chưa xuất phát
        assertEquals(200, step("dispatch", pickupOp, 3).statusCode());
        assertEquals(404, step("arrive", pickupOp, 4).statusCode());  // sai đầu
        assertEquals(404, step("arrive", outsider, 4).statusCode());
        var r = step("arrive", dropoffOp, 4);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("AWAITING_PICKUP", json(r).get("status").asString());
        assertEquals(dropoffOp.user().getId(), history.findByOrderIdOrderByOrderVersionAsc(orderId).getLast().getActorId());
        assertEquals(409, step("arrive", dropoffOp, 5).statusCode());
        assertEquals(1, count(OrderAction.ARRIVE));
        assertEquals(2, Capture.EVENTS.size());
    }

    @Test void timelineIsOrderedSurvivesReloadAndHidesActorIds() throws Exception {
        assertEquals(200, step("dispatch", pickupOp, 3).statusCode());
        var r = tracking(customer);
        assertEquals(200, r.statusCode(), r.body());
        assertFalse(r.body().contains("actorId"));
        var timeline = json(r).get("timeline");
        assertEquals(5, timeline.size());
        for (int i = 0; i < 5; i++) assertEquals(i, timeline.get(i).get("orderVersion").asInt());
        assertEquals(1, timeline.get(2).get("attemptNo").asInt());
        assertEquals(timeline.toString(), json(tracking(customer)).get("timeline").toString());
        assertEquals(history.findByOrderIdOrderByOrderVersionAsc(orderId).getLast().getId().toString(),
                timeline.get(4).get("eventId").asString());
    }

    @Test void trackingFollowsOrderScope() throws Exception {
        for (var a : List.of(customer, pickupOp, dropoffOp, dispatcher)) assertEquals(200, tracking(a).statusCode());
        assertEquals(404, tracking(otherCustomer).statusCode());
        assertEquals(404, tracking(outsider).statusCode());
        assertEquals(404, call("GET", "/orders/" + UUID.randomUUID() + "/tracking", customer.token(), null).statusCode());
        assertEquals(401, call("GET", "/orders/" + orderId + "/tracking", null, null).statusCode());
    }

    @Test void customerDoesNotSeeInternalNotes() throws Exception {
        assertEquals(200, step("dispatch", pickupOp, 3).statusCode());
        assertTrue(json(tracking(customer)).get("timeline").get(4).get("reason").isNull());
        assertEquals("Drone DR-01", json(tracking(pickupOp)).get("timeline").get(4).get("reason").asString());
    }

    @Test void availableActionsFollowStationAndStatus() throws Exception {
        assertEquals("[\"DISPATCH\"]", json(tracking(pickupOp)).get("allowedActions").toString());
        assertEquals("[]", json(tracking(dropoffOp)).get("allowedActions").toString());
        assertEquals("[]", json(tracking(customer)).get("allowedActions").toString());
        assertEquals(200, step("dispatch", pickupOp, 3).statusCode());
        assertEquals("[\"ARRIVE\"]", json(tracking(dropoffOp)).get("allowedActions").toString());
    }

    @Test void positionIsSimulatedOnlyInFlightAndNoLiveGps() throws Exception {
        var v = json(tracking(customer));
        assertEquals("AT_PICKUP", v.get("position").get("phase").asString());
        assertFalse(v.get("map").get("liveGpsAvailable").asBoolean());
        assertEquals(200, step("dispatch", pickupOp, 3).statusCode());
        var p = json(tracking(customer)).get("position");
        assertEquals("IN_FLIGHT", p.get("phase").asString());
        assertTrue(p.get("simulated").asBoolean());
    }

    @Test void missingStationIsReportedWithoutCoordinates() throws Exception {
        var o = orders.saveAndFlush(new Order(customer.user().getId(), pickup.getId(), UUID.randomUUID(),
                new RecipientSnapshot("QA", "0901234567", "QA", 10.8, 106.7), Instant.now()));
        var r = call("GET", "/orders/" + o.getId() + "/tracking", customer.token(), null);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(10.7769, json(r).get("pickup").get("latitude").asDouble(), 1e-9);
        assertTrue(json(r).get("dropoff").get("latitude").isNull());
    }
}
