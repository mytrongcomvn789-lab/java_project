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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.orders.OrderReceiptService;
import com.smartdrone.application.orders.dto.ReceivePackageRequest;
import com.smartdrone.application.workflow.*;
import com.smartdrone.domain.entity.*;
import com.smartdrone.domain.enums.*;
import com.smartdrone.infrastructure.persistence.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:receipt45;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
    "app.jwt.key=receipt45-test-signing-key-longer-than-32-characters",
    "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class OrderReceiptIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired StationRepository stations;
    @Autowired StaffStationAssignmentRepository assignments;
    @Autowired OrderRepository orders;
    @Autowired OrderPackageRepository packages;
    @Autowired DeliveryAttemptRepository attempts;
    @Autowired OrderStatusHistoryRepository history;
    @Autowired AccessTokenIssuer tokens;
    @Autowired OrderReceiptService receipts;
    @Autowired OrderTransitionService transitions;
    @Autowired PlatformTransactionManager txManager;
    Actor customer, dispatcher, operator, second, destination;
    Station pickup, dropoff;
    UUID orderId, packageId, attemptId, operatorAssignmentId;
    record Actor(User user, String token) { }

    private Actor actor(RoleName role) {
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@receipt.test", "unused",
                "Receipt QA", roles.findByName(role).orElseThrow()));
        return new Actor(user, tokens.issue(user));
    }
    private StaffStationAssignment assign(Actor a, Station s) {
        return assignments.saveAndFlush(new StaffStationAssignment(a.user(), s, Instant.now()));
    }

    @BeforeEach void setup() {
        customer = actor(RoleName.CUSTOMER); dispatcher = actor(RoleName.DISPATCHER);
        operator = actor(RoleName.STATION_OPERATOR); second = actor(RoleName.STATION_OPERATOR);
        destination = actor(RoleName.STATION_OPERATOR);
        pickup = stations.saveAndFlush(new Station("Pickup receipt QA", 10.7, 106.7, 10));
        dropoff = stations.saveAndFlush(new Station("Dropoff receipt QA", 10.8, 106.8, 10));
        operatorAssignmentId = assign(operator, pickup).getId(); assign(second, pickup); assign(destination, dropoff);
        var o = orders.saveAndFlush(new Order(customer.user().getId(), pickup.getId(), dropoff.getId(),
                new RecipientSnapshot("Test recipient", "0901234567", "Test address", 10.7, 106.7), Instant.now()));
        orderId = o.getId();
        var pkg = new OrderPackage(orderId, Instant.now());
        pkg.update("Receipt test parcel", 500, 20, 15, 10, List.of(), Instant.now());
        packageId = packages.saveAndFlush(pkg).getId();
        // Fixture uses the existing workflow, not a production scheduling endpoint.
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            var order = orders.findById(orderId).orElseThrow();
            transitions.recordCreation(order, customer.user().getId(), RoleName.CUSTOMER);
            transitions.transition(order, new TransitionCommand(OrderAction.APPROVE, dispatcher.user().getId(),
                    RoleName.DISPATCHER, 0, null, null, null));
            var attempt = attempts.saveAndFlush(new DeliveryAttempt(orderId, 1, pickup.getId(), dropoff.getId(),
                    Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
                    dispatcher.user().getId(), Instant.now()));
            attemptId = attempt.getId();
            transitions.transition(order, new TransitionCommand(OrderAction.SCHEDULE, dispatcher.user().getId(),
                    RoleName.DISPATCHER, 1, null, attemptId, null));
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
    private Map<String, Object> body() {
        return Map.of("attemptId", attemptId, "packageId", packageId, "note", "  Đã kiểm tra nguyên vẹn  ");
    }
    private HttpResponse<String> receive(Actor a, long version) throws Exception {
        return call("POST", "/orders/" + orderId + "/receipts", a.token(), body(), "If-Match", "\"" + version + "\"");
    }
    private JsonNode json(HttpResponse<String> response) { return JSON.readTree(response.body()); }
    private void unchanged() {
        var order = orders.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.SCHEDULED, order.getStatus());
        assertEquals(2, order.getVersion());
        assertEquals(3, history.findByOrderIdOrderByOrderVersionAsc(orderId).size());
    }

    @Test void receivePersistsStateActorTimeNoteAttemptAndEtag() throws Exception {
        var before = Instant.now().minusSeconds(1);
        var response = receive(operator, 2);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("PREPARING", json(response).get("status").asString());
        assertEquals("\"3\"", response.headers().firstValue("ETag").orElseThrow());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        var entries = history.findByOrderIdOrderByOrderVersionAsc(orderId);
        assertEquals(4, entries.size());
        var entry = entries.getLast();
        assertEquals(OrderAction.RECEIVE, entry.getAction());
        assertEquals(operator.user().getId(), entry.getActorId());
        assertEquals(RoleName.STATION_OPERATOR, entry.getActorRole());
        assertEquals(attemptId, entry.getAttemptId());
        assertEquals("Đã kiểm tra nguyên vẹn", entry.getReason());
        assertTrue(entry.getOccurredAt().isAfter(before));
        assertEquals(AttemptStatus.ACTIVE, attempts.findById(attemptId).orElseThrow().getStatus());
        assertEquals("PREPARING", json(call("GET", "/orders/" + orderId, customer.token(), null)).get("status").asString());
    }

    @Test void queueAndContextOnlyExposePickupAssignments() throws Exception {
        String path = "/station-receipts?code=" + orderId;
        assertEquals(1, json(call("GET", path, operator.token(), null)).get("total").asInt());
        assertEquals(0, json(call("GET", path, destination.token(), null)).get("total").asInt());
        assertEquals(0, json(call("GET", path + "&stationId=" + dropoff.getId(), operator.token(), null)).get("total").asInt());
        var context = call("GET", "/orders/" + orderId + "/receipt-context", operator.token(), null);
        assertEquals(200, context.statusCode());
        assertEquals(packageId.toString(), json(context).get("packageId").asString());
        assertEquals(attemptId.toString(), json(context).get("attempt").get("id").asString());
        assertEquals("\"2\"", context.headers().firstValue("ETag").orElseThrow());
        assertEquals(404, call("GET", "/orders/" + orderId + "/receipt-context", destination.token(), null).statusCode());
        assertEquals(404, receive(destination, 2).statusCode());
        assertEquals(404, receive(actor(RoleName.STATION_OPERATOR), 2).statusCode());
        unchanged();
    }

    @ParameterizedTest @EnumSource(value = RoleName.class,
        names = {"CUSTOMER", "DISPATCHER", "LOGISTICS_MANAGER", "SYSTEM_ADMIN"})
    void wrongRolesCannotReadQueueOrReceive(RoleName role) throws Exception {
        var who = actor(role); assign(who, pickup);
        assertEquals(403, receive(who, 2).statusCode());
        assertEquals(403, call("GET", "/station-receipts", who.token(), null).statusCode());
        assertEquals(403, call("GET", "/orders/" + orderId + "/receipt-context", who.token(), null).statusCode());
        unchanged();
    }

    @Test void anonymousCannotReceive() throws Exception {
        assertEquals(401, call("POST", "/orders/" + orderId + "/receipts", null, body(), "If-Match", "\"2\"").statusCode());
        unchanged();
    }
    @Test void revokedAssignmentIsCheckedAgainOnWrite() throws Exception {

        assertEquals(200, call("GET", "/orders/" + orderId + "/receipt-context", operator.token(), null).statusCode());
        assignments.deleteById(operatorAssignmentId);
        assertEquals(404, receive(operator, 2).statusCode());
        unchanged();
    }
    @Test void invalidBodyCannotChangeOrder() throws Exception {
        for (var payload : List.of(Map.of(), Map.of("attemptId", attemptId),
                Map.of("attemptId", attemptId, "packageId", packageId, "note", "x".repeat(501)),
                Map.of("attemptId", "bad-uuid", "packageId", packageId),
                Map.of("attemptId", attemptId, "packageId", packageId, "actorId", UUID.randomUUID()))) {
            assertEquals(400, call("POST", "/orders/" + orderId + "/receipts", operator.token(), payload,
                    "If-Match", "\"2\"").statusCode());
        }
        unchanged();
    }
    @Test void wrongParcelAndAttemptAreRejected() throws Exception {
        var wrongPackage = Map.of("attemptId", attemptId, "packageId", UUID.randomUUID());
        assertEquals(400, call("POST", "/orders/" + orderId + "/receipts", operator.token(),
                wrongPackage, "If-Match", "\"2\"").statusCode());
        var wrongAttempt = Map.of("attemptId", UUID.randomUUID(), "packageId", packageId);
        assertEquals(409, call("POST", "/orders/" + orderId + "/receipts", operator.token(),
                wrongAttempt, "If-Match", "\"2\"").statusCode());
        unchanged();
    }
    @Test void releasedAttemptCannotBeReceived() throws Exception {
        var attempt = attempts.findById(attemptId).orElseThrow();
        attempt.release(Instant.now()); attempts.saveAndFlush(attempt);
        assertEquals(409, receive(operator, 2).statusCode());
        unchanged();
    }
    @Test void differentAttemptStationCannotBeReceived() throws Exception {
        var old = attempts.findById(attemptId).orElseThrow(); old.release(Instant.now()); attempts.saveAndFlush(old);
        attemptId = attempts.saveAndFlush(new DeliveryAttempt(orderId, 2, dropoff.getId(), pickup.getId(),
                Instant.now(), Instant.now().plusSeconds(3600), dispatcher.user().getId(), Instant.now())).getId();
        assertEquals(409, receive(operator, 2).statusCode()); unchanged();
    }
    @Test void activeAttemptNotInScheduleHistoryCannotBeReceived() throws Exception {
        var old = attempts.findById(attemptId).orElseThrow(); old.release(Instant.now()); attempts.saveAndFlush(old);
        attemptId = attempts.saveAndFlush(new DeliveryAttempt(orderId, 2, pickup.getId(), dropoff.getId(),
                Instant.now(), Instant.now().plusSeconds(3600), dispatcher.user().getId(), Instant.now())).getId();
        assertEquals(409, receive(operator, 2).statusCode()); unchanged();
    }
    @Test void ambiguousActiveAttemptsAreRejectedWithout500() throws Exception {
        attempts.saveAndFlush(new DeliveryAttempt(orderId, 2, pickup.getId(), dropoff.getId(),
                Instant.now(), Instant.now().plusSeconds(3600), dispatcher.user().getId(), Instant.now()));
        assertEquals(409, receive(operator, 2).statusCode()); unchanged();
    }
    @Test void missingMalformedAndStaleVersionAreRejected() throws Exception {
        String path = "/orders/" + orderId + "/receipts";
        assertEquals(428, call("POST", path, operator.token(), body()).statusCode());
        assertEquals(400, call("POST", path, operator.token(), body(), "If-Match", "bad").statusCode());
        assertEquals(412, receive(operator, 0).statusCode()); unchanged();
    }
    @Test void unscheduledOrderCannotBeReceived() throws Exception {
        var pending = orders.saveAndFlush(new Order(customer.user().getId(), pickup.getId(), dropoff.getId(),
                new RecipientSnapshot("Test", "0901234567", "Test address", 10.7, 106.7), Instant.now()));
        assertEquals(409, call("POST", "/orders/" + pending.getId() + "/receipts", operator.token(),
                body(), "If-Match", "\"0\"").statusCode());
        assertEquals(OrderStatus.PENDING, orders.findById(pending.getId()).orElseThrow().getStatus());
    }
    @Test void repeatedRequestDoesNotDuplicateHistoryAndLeavesQueue() throws Exception {
        assertEquals(200, receive(operator, 2).statusCode());
        assertEquals(412, receive(operator, 2).statusCode());
        assertEquals(409, receive(operator, 3).statusCode());
        assertEquals(4, history.findByOrderIdOrderByOrderVersionAsc(orderId).size());
        assertEquals(0, json(call("GET", "/station-receipts?code=" + orderId, operator.token(), null)).get("total").asInt());
    }
    @RepeatedTest(3) void concurrentReceiversProduceExactlyOneReceipt() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); return receive(operator, 2); });
            var b = pool.submit(() -> { start.await(); return receive(second, 2); });
            start.countDown();
            var ra = a.get(30, TimeUnit.SECONDS); var rb = b.get(30, TimeUnit.SECONDS);
            assertEquals(List.of(200, 412), java.util.stream.Stream.of(ra, rb).map(HttpResponse::statusCode).sorted().toList(),
                    ra.body() + rb.body());
        }
        assertEquals(3, orders.findById(orderId).orElseThrow().getVersion());
        assertEquals(1, history.findByOrderIdOrderByOrderVersionAsc(orderId).stream().filter(h -> h.getAction() == OrderAction.RECEIVE).count());
    }
    @Test @WithMockUser(roles = "STATION_OPERATOR")
    void rollbackRevertsBothReceiptAndState() {
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            receipts.receive(operator.user().getId(), orderId, 2, new ReceivePackageRequest(attemptId, packageId, "Test rollback"));
            tx.setRollbackOnly();
        });
        unchanged();
    }
}
