package com.smartdrone.api.orders;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.orders.*;
import com.smartdrone.domain.entity.*;
import com.smartdrone.domain.enums.*;
import com.smartdrone.infrastructure.persistence.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${REVIEW_TEST_DATABASE_URL:jdbc:h2:mem:review41;MODE=MSSQLServer;DB_CLOSE_DELAY=-1}",
    "spring.datasource.driver-class-name=${REVIEW_TEST_DATABASE_DRIVER:org.h2.Driver}",
    "spring.datasource.username=${REVIEW_TEST_DATABASE_USER:sa}",
    "spring.datasource.password=${REVIEW_TEST_DATABASE_PASSWORD:}",
    "app.jwt.key=review41-test-signing-key-longer-than-32-characters",
    "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
@Import(OrderReviewIntegrationTest.EventConfig.class)
class OrderReviewIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired StationRepository stations;
    @Autowired StaffStationAssignmentRepository assignments;
    @Autowired OrderRepository orders;
    @Autowired OrderStatusHistoryRepository history;
    @Autowired AccessTokenIssuer tokens;
    @Autowired OrderReviewService reviews;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired Events events;
    private Actor customer, dispatcher, other;
    private Station pickup, dropoff, outside;
    private UUID orderId;

    record Actor(User user, String token) { }

    @TestConfiguration
    static class EventConfig {
        @Bean Events reviewEvents() { return new Events(); }
    }
    static class Events {
        final List<OrderReviewedEvent> received = new CopyOnWriteArrayList<>();
        volatile UUID failingOrder;
        @EventListener
        public void receive(OrderReviewedEvent event) {
            received.add(event);
            if (event.orderId().equals(failingOrder)) throw new IllegalStateException("Simulated realtime failure");
        }
        List<OrderReviewedEvent> forOrder(UUID id) {
            return received.stream().filter(e -> e.orderId().equals(id)).toList();
        }
    }

    @BeforeEach
    void setup() {
        customer = actor(RoleName.CUSTOMER);
        dispatcher = actor(RoleName.DISPATCHER);
        other = actor(RoleName.DISPATCHER);
        pickup = stations.saveAndFlush(new Station("Trạm gửi QA", 10.7, 106.7, 3));
        dropoff = stations.saveAndFlush(new Station("Trạm nhận QA", 10.8, 106.8, 3));
        outside = stations.saveAndFlush(new Station("Trạm ngoài phạm vi QA", 10.9, 106.9, 3));
        assign(dispatcher, pickup);
        orderId = seedOrder(pickup, dropoff);
    }
    private Actor actor(RoleName role) {
        var u = users.saveAndFlush(new User(UUID.randomUUID() + "@review.test", "unused",
                "Review QA", roles.findByName(role).orElseThrow()));
        return new Actor(u, tokens.issue(u));
    }
    private StaffStationAssignment assign(Actor a, Station s) {
        return assignments.saveAndFlush(new StaffStationAssignment(a.user(), s, Instant.now()));
    }
    private UUID seedOrder(Station from, Station to) {
        var o = orders.saveAndFlush(new Order(customer.user().getId(), from.getId(), to.getId(),
                new RecipientSnapshot("Người nhận QA", "0901234567", "Địa chỉ thử", 10.7, 106.7),
                Instant.now().minusSeconds(60)));
        history.saveAndFlush(new OrderStatusHistory(o.getId(), 0, null, OrderStatus.PENDING, null,
                customer.user().getId(), RoleName.CUSTOMER, ChangeSource.API, null, null, null, Instant.now()));
        return o.getId();
    }
    private HttpResponse<String> call(String method, String path, String token, Object body, String... headers)
            throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i+1]);
        return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> decide(Actor a, String action, long version) throws Exception {
        return call("POST", "/orders/" + orderId + "/" + action, a.token(),
                action.equals("reject") ? Map.of("reason", "Không đáp ứng điều kiện") : null,
                "If-Match", "\"" + version + "\"");
    }
    private JsonNode json(HttpResponse<String> r) { return JSON.readTree(r.body()); }
    private void unchanged() {
        var o = orders.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.PENDING, o.getStatus());
        assertEquals(0, o.getVersion());
        assertEquals(1, history.findByOrderIdOrderByOrderVersionAsc(orderId).size());
        assertTrue(events.forOrder(orderId).isEmpty());
    }

    @Test void approvesAndPersistsAuditAndCommittedEvent() throws Exception {
        var before = orders.findById(orderId).orElseThrow().getUpdatedAt();
        var r = decide(dispatcher, "approve", 0);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("APPROVED", json(r).get("status").asString());
        assertEquals("\"1\"", r.headers().firstValue("ETag").orElseThrow());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElseThrow());
        var o = orders.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.APPROVED, o.getStatus());
        assertTrue(o.getUpdatedAt().isAfter(before));
        var h = history.findByOrderIdOrderByOrderVersionAsc(orderId);
        assertEquals(2, h.size());
        assertEquals(dispatcher.user().getId(), h.get(1).getActorId());
        assertEquals(RoleName.DISPATCHER, h.get(1).getActorRole());
        assertEquals(ChangeSource.API, h.get(1).getSource());
        assertEquals(OrderAction.APPROVE, h.get(1).getAction());
        assertEquals(1, h.get(1).getOrderVersion());
        assertNotNull(h.get(1).getOccurredAt());
        assertNull(h.get(1).getReason());
        var emitted = events.forOrder(orderId);
        assertEquals(1, emitted.size());
        assertEquals(h.get(1).getId(), emitted.getFirst().eventId());
        assertEquals(customer.user().getId(), emitted.getFirst().customerId());
        assertEquals(OrderStatus.APPROVED, emitted.getFirst().toStatus());
        assertEquals(1, emitted.getFirst().version());
    }

    @Test void rejectsWithTrimmedVietnameseReason() throws Exception {
        String reason = "Hàng dễ vỡ, chưa đủ điều kiện vận chuyển";
        var r = call("POST", "/orders/" + orderId + "/reject", dispatcher.token(),
                Map.of("reason", "  " + reason + "  "), "If-Match", "\"0\"");
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("REJECTED", json(r).get("status").asString());
        assertEquals(reason, history.findByOrderIdOrderByOrderVersionAsc(orderId).get(1).getReason());
        assertEquals(reason, events.forOrder(orderId).getFirst().reason());
        var customerView = call("GET", "/orders/" + orderId, customer.token(), null);
        assertEquals(reason, json(customerView).get("history").get(1).get("reason").asString());
    }

    @Test void rejectionRequiresNonblankBoundedReasonAndValidJson() throws Exception {
        for (Object body : List.of(Map.of(), Map.of("reason", ""), Map.of("reason", "   "),
                Map.of("reason", "x".repeat(501)), Map.of("reason", "valid", "actorId", UUID.randomUUID()))) {
            var r = call("POST", "/orders/" + orderId + "/reject", dispatcher.token(), body, "If-Match", "\"0\"");
            assertEquals(400, r.statusCode(), r.body());
        }
        assertEquals(400, call("POST", "/orders/" + orderId + "/reject", dispatcher.token(), null,
                "If-Match", "\"0\"").statusCode());
        unchanged();
    }

    @ParameterizedTest
    @EnumSource(value = RoleName.class, names = {"CUSTOMER", "SYSTEM_ADMIN", "STATION_OPERATOR", "LOGISTICS_MANAGER"})
    void wrongRolesCannotApproveOrRejectEvenWhenAssigned(RoleName role) throws Exception {
        var a = actor(role); assign(a, pickup);
        assertEquals(403, decide(a, "approve", 0).statusCode());
        assertEquals(403, decide(a, "reject", 0).statusCode());
        unchanged();
    }

    @Test void anonymousCannotReview() throws Exception {
        assertEquals(401, call("POST", "/orders/" + orderId + "/approve", null, null).statusCode());
        assertEquals(401, call("POST", "/orders/" + orderId + "/reject", null, Map.of("reason", "x")).statusCode());
        unchanged();
    }

    @Test void unassignedAndOutsideStationAreHiddenForReadAndWrite() throws Exception {
        assertEquals(0, json(call("GET", "/orders", other.token(), null)).get("total").asInt());
        assertEquals(404, decide(other, "approve", 0).statusCode());
        assign(other, outside);
        assertEquals(404, decide(other, "reject", 0).statusCode());
        assertEquals(404, call("GET", "/orders/" + orderId, other.token(), null).statusCode());
        assertEquals(404, call("POST", "/orders/" + UUID.randomUUID() + "/approve",
                dispatcher.token(), null, "If-Match", "\"0\"").statusCode());
        unchanged();
    }

    @Test void dropoffAssignmentUsesSameScopeAsOrderListAndReview() throws Exception {
        assign(other, dropoff);
        var list = call("GET", "/orders", other.token(), null);
        assertEquals(200, list.statusCode());
        assertEquals(1, json(list).get("total").asInt());
        assertEquals(orderId.toString(), json(list).get("items").get(0).get("id").asString());
        assertEquals(200, decide(other, "approve", 0).statusCode());
    }

    @Test void revokingAssignmentRemovesAccessWithoutReissuingJwt() throws Exception {
        var assignment = assign(other, pickup);
        assertEquals(200, call("GET", "/orders/" + orderId, other.token(), null).statusCode());
        assignments.deleteById(assignment.getId());
        assertEquals(404, decide(other, "approve", 0).statusCode());
        unchanged();
    }

    @Test void missingMalformedAndStaleIfMatchDoNotChangeOrder() throws Exception {
        for (String action : List.of("approve", "reject")) {
            var body = action.equals("reject") ? Map.of("reason", "x") : null;
            String route = "/orders/" + orderId + "/" + action;
            assertEquals(428, call("POST", route, dispatcher.token(), body).statusCode());
            assertEquals(400, call("POST", route, dispatcher.token(), body, "If-Match", "bad").statusCode());
            var stale = call("POST", route, dispatcher.token(), body, "If-Match", "\"9\"");
            assertEquals(412, stale.statusCode());
            assertEquals("VERSION_CONFLICT", json(stale).get("code").asString());
        }
        unchanged();
    }

    @Test void processedOrderRejectsAnotherDecisionWithCurrentVersion() throws Exception {
        assertEquals(200, decide(dispatcher, "approve", 0).statusCode());
        assertEquals(409, decide(dispatcher, "reject", 1).statusCode());
        assertEquals(409, decide(dispatcher, "approve", 1).statusCode());
        assertEquals(2, history.findByOrderIdOrderByOrderVersionAsc(orderId).size());
        assertEquals(1, events.forOrder(orderId).size());
    }

    @Test void cancelledOrderCannotBeReviewed() throws Exception {
        assertEquals(200, call("POST", "/orders/" + orderId + "/cancel", customer.token(), null,
                "If-Match", "\"0\"").statusCode());
        assertEquals(409, decide(dispatcher, "approve", 1).statusCode());
        assertEquals(409, decide(dispatcher, "reject", 1).statusCode());
        assertTrue(events.forOrder(orderId).isEmpty());
    }

    @RepeatedTest(3)
    void concurrentApproveAndRejectProduceExactlyOneDecisionAndEvent() throws Exception {
        assign(other, dropoff);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return decide(dispatcher, "approve", 0); });
            var b = pool.submit(() -> { start.await(); return decide(other, "reject", 0); });
            start.countDown();
            var ra = a.get(30, TimeUnit.SECONDS);
            var rb = b.get(30, TimeUnit.SECONDS);
            assertEquals(List.of(200, 412), java.util.stream.Stream.of(ra, rb)
                    .map(HttpResponse::statusCode).sorted().toList(), ra.body() + rb.body());
        }
        assertEquals(1, orders.findById(orderId).orElseThrow().getVersion());
        assertEquals(2, history.findByOrderIdOrderByOrderVersionAsc(orderId).size());
        assertEquals(1, events.forOrder(orderId).size());
        assertEquals(orders.findById(orderId).orElseThrow().getStatus(), events.forOrder(orderId).getFirst().toStatus());
    }

    @Test @WithMockUser(roles = "DISPATCHER")
    void rollbackRevertsStatusAndHistoryAndDoesNotPublish() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            reviews.approve(dispatcher.user().getId(), orderId, 0);
            assertTrue(events.forOrder(orderId).isEmpty(), "must not publish before commit");
            tx.setRollbackOnly();
        });
        unchanged();
    }

    @Test void eventConsumerFailureDoesNotUndoCommittedReviewOrFailRest() throws Exception {
        events.failingOrder = orderId;
        try {
            assertEquals(200, decide(dispatcher, "approve", 0).statusCode());
            assertEquals(OrderStatus.APPROVED, orders.findById(orderId).orElseThrow().getStatus());
            assertEquals(2, history.findByOrderIdOrderByOrderVersionAsc(orderId).size());
        } finally { events.failingOrder = null; }
    }

    @Test void corsExposesEtagToAllowedBrowserOrigin() throws Exception {
        var r = call("GET", "/orders/" + orderId, dispatcher.token(), null, "Origin", "http://localhost:5173");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(r.headers().firstValue("Access-Control-Expose-Headers").orElse("").contains("ETag"));
        assertEquals("http://localhost:5173", r.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals("\"0\"", r.headers().firstValue("ETag").orElseThrow());
    }
}
