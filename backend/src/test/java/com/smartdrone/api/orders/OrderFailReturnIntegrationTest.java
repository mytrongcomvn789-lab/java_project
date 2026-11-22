package com.smartdrone.api.orders;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.scheduling.ScheduleProperties;
import com.smartdrone.application.workflow.OrderTransitionService;
import com.smartdrone.application.workflow.TransitionCommand;
import com.smartdrone.domain.entity.*;
import com.smartdrone.domain.enums.*;
import com.smartdrone.infrastructure.persistence.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 4.8: kiem thu API bao that bai, giao lai, bat dau hoan va hoan xong qua HTTP that, JWT that, H2 rieng.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${FAILRETURN_TEST_DATABASE_URL:jdbc:h2:mem:failreturn48;MODE=MSSQLServer;DB_CLOSE_DELAY=-1}",
    "spring.datasource.driver-class-name=${FAILRETURN_TEST_DATABASE_DRIVER:org.h2.Driver}",
    "spring.datasource.username=${FAILRETURN_TEST_DATABASE_USER:sa}",
    "spring.datasource.password=${FAILRETURN_TEST_DATABASE_PASSWORD:}",
    "app.jwt.key=failreturn48-test-signing-key-longer-than-32-characters",
    "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class OrderFailReturnIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired StationRepository stations;
    @Autowired StaffStationAssignmentRepository assignments;
    @Autowired OrderRepository orders;
    @Autowired OrderStatusHistoryRepository history;
    @Autowired DeliveryAttemptRepository attempts;
    @Autowired AccessTokenIssuer tokens;
    @Autowired OrderTransitionService transitions;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ScheduleProperties props;

    private Actor customer, dispatcher, otherDispatcher, pickupOp, dropoffOp, strangerOp;
    private Station pickup, dropoff, tight;

    record Actor(User user, String token) { }

    @BeforeEach
    void setup() {
        customer = actor(RoleName.CUSTOMER);
        dispatcher = actor(RoleName.DISPATCHER);
        otherDispatcher = actor(RoleName.DISPATCHER); // khong duoc phan cong tram nao
        pickupOp = actor(RoleName.STATION_OPERATOR);
        dropoffOp = actor(RoleName.STATION_OPERATOR);
        strangerOp = actor(RoleName.STATION_OPERATOR);
        pickup = stations.saveAndFlush(new Station("Tram gui 48", 10.7, 106.7, 3));
        dropoff = stations.saveAndFlush(new Station("Tram nhan 48", 10.8, 106.8, 3));
        tight = stations.saveAndFlush(new Station("Tram chat 48", 10.6, 106.6, 1)); // suc chua 1
        assign(dispatcher, pickup);
        assign(dispatcher, dropoff);
        assign(dispatcher, tight);
        assign(pickupOp, pickup);
        assign(pickupOp, tight);
        assign(dropoffOp, dropoff);
    }

    // ---------- ham tro giup ----------

    private Actor actor(RoleName role) {
        var u = users.saveAndFlush(new User(UUID.randomUUID() + "@failreturn.test", "unused",
                "FailReturn QA", roles.findByName(role).orElseThrow()));
        return new Actor(u, tokens.issue(u));
    }

    private void assign(Actor a, Station s) {
        assignments.saveAndFlush(new StaffStationAssignment(a.user(), s, Instant.now()));
    }

    private UUID seedOrder(Station from, Station to) {
        var o = orders.saveAndFlush(new Order(customer.user().getId(), from.getId(), to.getId(),
                new RecipientSnapshot("Nguoi nhan QA", "0901234567", "Dia chi thu", 10.7, 106.7),
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
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String v(long version) { return "\"" + version + "\""; }

    private HttpResponse<String> fail(Actor a, UUID id, long version, Object body) throws Exception {
        return call("POST", "/orders/" + id + "/fail", a.token(), body, "If-Match", v(version));
    }

    private HttpResponse<String> startReturn(Actor a, UUID id, long version, Object body) throws Exception {
        return call("POST", "/orders/" + id + "/return", a.token(), body, "If-Match", v(version));
    }

    private HttpResponse<String> completeReturn(Actor a, UUID id, long version) throws Exception {
        return call("POST", "/orders/" + id + "/complete-return", a.token(), null, "If-Match", v(version));
    }

    private HttpResponse<String> schedule(Actor a, UUID id, long version, Instant slot) throws Exception {
        return call("POST", "/orders/" + id + "/schedules", a.token(),
                Map.of("slotStart", slot.toString()), "If-Match", v(version));
    }

    private static Map<String, Object> failBody(String code, String note) {
        return note == null ? Map.of("reasonCode", code) : Map.of("reasonCode", code, "note", note);
    }

    private JsonNode json(HttpResponse<String> r) { return JSON.readTree(r.body()); }

    private long step() { return props.slotMinutes() * 60L; }

    private Instant slotAfter(Duration fromNow) {
        long t = Instant.now().plus(fromNow).getEpochSecond();
        return Instant.ofEpochSecond(((t + step() - 1) / step()) * step());
    }

    /** Tao don, duyet va len lich qua API: tra ve don SCHEDULED o phien ban 2. */
    private UUID scheduledOrder(Station from, Station to, Instant slot) throws Exception {
        UUID id = seedOrder(from, to);
        var a = call("POST", "/orders/" + id + "/approve", dispatcher.token(), null, "If-Match", v(0));
        assertEquals(200, a.statusCode(), a.body());
        var s = schedule(dispatcher, id, 1, slot);
        assertEquals(200, s.statusCode(), s.body());
        return id;
    }

    /** Don FAILED o phien ban 3 (qua API). */
    private UUID failedOrder(Station from, Station to, Instant slot) throws Exception {
        UUID id = scheduledOrder(from, to, slot);
        var r = fail(dispatcher, id, 2, failBody("WEATHER", null));
        assertEquals(200, r.statusCode(), r.body());
        return id;
    }

    /** Don RETURNING o phien ban 4 (qua API). */
    private UUID returningOrder(Station from, Station to, Instant slot) throws Exception {
        UUID id = failedOrder(from, to, slot);
        var r = startReturn(dispatcher, id, 3, Map.of("reason", "Khach khong nhan"));
        assertEquals(200, r.statusCode(), r.body());
        return id;
    }

    private void move(UUID id, OrderAction action, RoleName role) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var o = orders.findById(id).orElseThrow();
            transitions.transition(o, new TransitionCommand(action, dispatcher.user().getId(),
                    role, o.getVersion(), null, null, null));
        });
    }

    private long active(UUID orderId) {
        return attempts.findByOrderIdOrderByAttemptNoAsc(orderId).stream()
                .filter(a -> a.getStatus() == AttemptStatus.ACTIVE).count();
    }

    private void assertUnchanged(UUID id, OrderStatus status, long version, int historySize) {
        var o = orders.findById(id).orElseThrow();
        assertEquals(status, o.getStatus());
        assertEquals(version, o.getVersion());
        assertEquals(historySize, history.findByOrderIdOrderByOrderVersionAsc(id).size());
    }

    // ---------- FAIL ----------

    @Test void failRecordsReasonCodeAndReleasesSeat() throws Exception {
        UUID id = scheduledOrder(tight, dropoff, slotAfter(Duration.ofHours(2)));
        var r = fail(dispatcher, id, 2, failBody("RECIPIENT_ABSENT", "Goi 3 lan khong nghe may"));
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("FAILED", json(r).get("status").asString());
        assertEquals("\"3\"", r.headers().firstValue("ETag").orElseThrow());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElseThrow());

        var h = history.findByOrderIdOrderByOrderVersionAsc(id);
        var last = h.getLast();
        assertEquals(4, h.size());
        assertEquals(OrderAction.FAIL, last.getAction());
        assertEquals(OrderStatus.SCHEDULED, last.getFromStatus());
        assertEquals(OrderStatus.FAILED, last.getToStatus());
        assertEquals(FailureReason.RECIPIENT_ABSENT, last.getReasonCode());
        assertEquals("Goi 3 lan khong nghe may", last.getReason());
        assertEquals(dispatcher.user().getId(), last.getActorId());
        assertEquals(RoleName.DISPATCHER, last.getActorRole());
        var attempt = attempts.findByOrderIdOrderByAttemptNoAsc(id).getFirst();
        assertEquals(attempt.getId(), last.getAttemptId());
        assertEquals(AttemptStatus.RELEASED, attempt.getStatus());
        assertEquals(0, active(id));
    }

    @Test void failWithoutNoteUsesReasonLabel() throws Exception {
        UUID id = scheduledOrder(pickup, dropoff, slotAfter(Duration.ofHours(2)));
        assertEquals(200, fail(dispatcher, id, 2, failBody("DRONE_FAULT", null)).statusCode());
        var last = history.findByOrderIdOrderByOrderVersionAsc(id).getLast();
        assertEquals(FailureReason.DRONE_FAULT.label(), last.getReason());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void failIsAllowedFromEachInProgressState(int moves) throws Exception {
        UUID id = scheduledOrder(pickup, dropoff, slotAfter(Duration.ofHours(2)));
        OrderAction[] actions = {OrderAction.RECEIVE, OrderAction.DISPATCH, OrderAction.ARRIVE};
        for (int i = 0; i < moves; i++) move(id, actions[i], RoleName.STATION_OPERATOR);
        long version = orders.findById(id).orElseThrow().getVersion();
        var r = fail(dispatcher, id, version, failBody("WEATHER", null));
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("FAILED", json(r).get("status").asString());
        assertEquals(0, active(id));
    }

    @Test void failRequiresValidReasonAndLeavesOrderUntouched() throws Exception {
        UUID id = scheduledOrder(pickup, dropoff, slotAfter(Duration.ofHours(2)));
        assertEquals(400, fail(dispatcher, id, 2, Map.of()).statusCode());                       // thieu ma
        assertEquals(400, fail(dispatcher, id, 2, Map.of("note", "chi co ghi chu")).statusCode());
        assertEquals(400, fail(dispatcher, id, 2, failBody("NOT_A_REASON", null)).statusCode()); // ma la
        assertEquals(400, fail(dispatcher, id, 2, failBody("OTHER", null)).statusCode());        // OTHER can ghi chu
        assertEquals(400, fail(dispatcher, id, 2, failBody("OTHER", "   ")).statusCode());
        assertEquals(400, fail(dispatcher, id, 2, failBody("WEATHER", "x".repeat(501))).statusCode());
        assertEquals(400, fail(dispatcher, id, 2, Map.of("reasonCode", "WEATHER", "status", "DELIVERED"))
                .statusCode());                                                                  // truong la
        assertUnchanged(id, OrderStatus.SCHEDULED, 2, 3);
        assertEquals(1, active(id));
        assertEquals(200, fail(dispatcher, id, 2, failBody("OTHER", "Ly do rieng")).statusCode());
    }

    @Test void failRejectsMissingOrStaleVersion() throws Exception {
        UUID id = scheduledOrder(pickup, dropoff, slotAfter(Duration.ofHours(2)));
        assertEquals(428, call("POST", "/orders/" + id + "/fail", dispatcher.token(),
                failBody("WEATHER", null)).statusCode());
        assertEquals(400, call("POST", "/orders/" + id + "/fail", dispatcher.token(),
                failBody("WEATHER", null), "If-Match", "bad").statusCode());
        var stale = fail(dispatcher, id, 1, failBody("WEATHER", null));
        assertEquals(412, stale.statusCode());
        assertEquals("VERSION_CONFLICT", json(stale).get("code").asString());
        assertUnchanged(id, OrderStatus.SCHEDULED, 2, 3);
        assertEquals(1, active(id));
    }

    @Test void failIsRejectedFromWrongStatesAndOutOfScope() throws Exception {
        UUID pending = seedOrder(pickup, dropoff);
        var wrong = fail(dispatcher, pending, 0, failBody("WEATHER", null));
        assertEquals(409, wrong.statusCode(), wrong.body());
        assertEquals("INVALID_STATE_TRANSITION", json(wrong).get("code").asString());
        assertUnchanged(pending, OrderStatus.PENDING, 0, 1);

        UUID failed = failedOrder(pickup, dropoff, slotAfter(Duration.ofHours(2)));
        assertEquals(409, fail(dispatcher, failed, 3, failBody("WEATHER", null)).statusCode()); // da FAILED
        UUID returning = returningOrder(pickup, dropoff, slotAfter(Duration.ofHours(3)));
        assertEquals(409, fail(dispatcher, returning, 4, failBody("WEATHER", null)).statusCode());

        UUID id = scheduledOrder(pickup, dropoff, slotAfter(Duration.ofHours(4)));
        assertEquals(404, fail(otherDispatcher, id, 2, failBody("WEATHER", null)).statusCode()); // ngoai pham vi
        assertEquals(404, fail(dispatcher, UUID.randomUUID(), 2, failBody("WEATHER", null)).statusCode());
        assertUnchanged(id, OrderStatus.SCHEDULED, 2, 3);
        assertEquals(1, active(id));
    }

    @ParameterizedTest
    @EnumSource(value = RoleName.class, names = {"CUSTOMER", "SYSTEM_ADMIN", "STATION_OPERATOR", "LOGISTICS_MANAGER"})
    void otherRolesAndAnonymousCannotFailOrStartReturn(RoleName role) throws Exception {
        var a = actor(role);
        assign(a, pickup);
        UUID scheduled = scheduledOrder(pickup, dropoff, slotAfter(Duration.ofHours(2)));
        assertEquals(403, fail(a, scheduled, 2, failBody("WEATHER", null)).statusCode());
        assertEquals(401, call("POST", "/orders/" + scheduled + "/fail", null,
                failBody("WEATHER", null), "If-Match", v(2)).statusCode());
        assertUnchanged(scheduled, OrderStatus.SCHEDULED, 2, 3);

        UUID failed = failedOrder(pickup, dropoff, slotAfter(Duration.ofHours(3)));
        assertEquals(403, startReturn(a, failed, 3, Map.of("reason", "x")).statusCode());
        assertEquals(401, call("POST", "/orders/" + failed + "/return", null,
                Map.of("reason", "x"), "If-Match", v(3)).statusCode());
        assertUnchanged(failed, OrderStatus.FAILED, 3, 4);
    }

    @RepeatedTest(3)
    void doubleFailExactlyOneWins() throws Exception {
        UUID id = scheduledOrder(pickup, dropoff, slotAfter(Duration.ofHours(2)));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var f1 = pool.submit(() -> { start.await(); return fail(dispatcher, id, 2, failBody("WEATHER", null)); });
            var f2 = pool.submit(() -> { start.await(); return fail(dispatcher, id, 2, failBody("DRONE_FAULT", null)); });
            start.countDown();
            var r1 = f1.get(30, TimeUnit.SECONDS);
            var r2 = f2.get(30, TimeUnit.SECONDS);
            assertEquals(List.of(200, 412), Stream.of(r1, r2).map(HttpResponse::statusCode).sorted().toList(),
                    r1.body() + r2.body());
        }
        assertUnchanged(id, OrderStatus.FAILED, 3, 4);
        assertEquals(0, active(id));
    }

    // ---------- giao lai ----------

    @Test void failedSeatIsFreeAndRetryKeepsOldAttemptHistory() throws Exception {
        Instant slot = slotAfter(Duration.ofHours(2));
        UUID a = scheduledOrder(tight, dropoff, slot); // tight chi co 1 cho
        assertEquals(200, fail(dispatcher, a, 2, failBody("WEATHER", null)).statusCode());

        UUID b = seedOrder(tight, dropoff);            // don khac chiem lai cho vua tra
        assertEquals(200, call("POST", "/orders/" + b + "/approve", dispatcher.token(), null,
                "If-Match", v(0)).statusCode());
        assertEquals(200, schedule(dispatcher, b, 1, slot).statusCode());

        var full = schedule(dispatcher, a, 3, slot);   // cho da het, giao lai cung slot bi tu choi
        assertEquals(409, full.statusCode(), full.body());
        assertEquals("SLOT_FULL", json(full).get("code").asString());
        assertUnchanged(a, OrderStatus.FAILED, 3, 4);

        var retry = schedule(dispatcher, a, 3, slot.plusSeconds(step()));
        assertEquals(200, retry.statusCode(), retry.body());
        assertEquals("SCHEDULED", json(retry).get("status").asString());
        var list = attempts.findByOrderIdOrderByAttemptNoAsc(a);
        assertEquals(2, list.size());
        assertEquals(1, list.get(0).getAttemptNo());
        assertEquals(AttemptStatus.RELEASED, list.get(0).getStatus());
        assertEquals(slot, list.get(0).getSlotStart());          // lan cu giu nguyen
        assertEquals(2, list.get(1).getAttemptNo());
        assertEquals(AttemptStatus.ACTIVE, list.get(1).getStatus());
        assertNotEquals(list.get(0).getId(), list.get(1).getId());
        var h = history.findByOrderIdOrderByOrderVersionAsc(a);
        assertEquals(5, h.size());
        assertEquals(list.get(0).getId(), h.get(3).getAttemptId());  // dong FAIL van tro lan 1
        assertEquals(list.get(1).getId(), h.get(4).getAttemptId());  // dong SCHEDULE moi tro lan 2
    }

    @RepeatedTest(3)
    void doubleRetryCreatesOnlyOneNewAttempt() throws Exception {
        UUID id = failedOrder(pickup, dropoff, slotAfter(Duration.ofHours(2)));
        Instant s1 = slotAfter(Duration.ofHours(5));
        Instant s2 = s1.plusSeconds(step());
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var f1 = pool.submit(() -> { start.await(); return schedule(dispatcher, id, 3, s1); });
            var f2 = pool.submit(() -> { start.await(); return schedule(dispatcher, id, 3, s2); });
            start.countDown();
            var r1 = f1.get(30, TimeUnit.SECONDS);
            var r2 = f2.get(30, TimeUnit.SECONDS);
            assertEquals(1, Stream.of(r1, r2).filter(r -> r.statusCode() == 200).count(), r1.body() + r2.body());
            assertEquals(1, Stream.of(r1, r2).filter(r -> r.statusCode() == 412 || r.statusCode() == 409).count(),
                    r1.body() + r2.body());
        }
        assertEquals(2, attempts.findByOrderIdOrderByAttemptNoAsc(id).size());
        assertEquals(1, active(id));
        assertUnchanged(id, OrderStatus.SCHEDULED, 4, 5);
    }

    // ---------- START_RETURN ----------

    @Test void startReturnMovesFailedToReturningWithReason() throws Exception {
        UUID id = failedOrder(tight, dropoff, slotAfter(Duration.ofHours(2)));
        var r = startReturn(dispatcher, id, 3, Map.of("reason", "  Nguoi nhan tu choi  "));
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("RETURNING", json(r).get("status").asString());
        assertEquals("\"4\"", r.headers().firstValue("ETag").orElseThrow());
        var last = history.findByOrderIdOrderByOrderVersionAsc(id).getLast();
        assertEquals(OrderAction.START_RETURN, last.getAction());
        assertEquals(OrderStatus.FAILED, last.getFromStatus());
        assertEquals(OrderStatus.RETURNING, last.getToStatus());
        assertEquals("Nguoi nhan tu choi", last.getReason());
        assertNull(last.getReasonCode());
        assertEquals(dispatcher.user().getId(), last.getActorId());
        assertEquals(0, active(id));
    }

    @Test void startReturnRejectsBadRequests() throws Exception {
        UUID id = failedOrder(pickup, dropoff, slotAfter(Duration.ofHours(2)));
        assertEquals(400, startReturn(dispatcher, id, 3, Map.of()).statusCode());
        assertEquals(400, startReturn(dispatcher, id, 3, Map.of("reason", "   ")).statusCode());
        assertEquals(400, startReturn(dispatcher, id, 3, Map.of("reason", "x".repeat(501))).statusCode());
        assertEquals(400, startReturn(dispatcher, id, 3, Map.of("reason", "ok", "status", "RETURNED"))
                .statusCode());
        assertEquals(428, call("POST", "/orders/" + id + "/return", dispatcher.token(),
                Map.of("reason", "ok")).statusCode());
        var stale = startReturn(dispatcher, id, 2, Map.of("reason", "ok"));
        assertEquals(412, stale.statusCode());
        assertEquals(404, startReturn(otherDispatcher, id, 3, Map.of("reason", "ok")).statusCode());
        assertUnchanged(id, OrderStatus.FAILED, 3, 4);
    }

    @Test void startReturnOnlyFromFailed() throws Exception {
        UUID scheduled = scheduledOrder(pickup, dropoff, slotAfter(Duration.ofHours(2)));
        var r = startReturn(dispatcher, scheduled, 2, Map.of("reason", "ok"));
        assertEquals(409, r.statusCode(), r.body());
        assertEquals("INVALID_STATE_TRANSITION", json(r).get("code").asString());
        assertUnchanged(scheduled, OrderStatus.SCHEDULED, 2, 3);
        assertEquals(1, active(scheduled)); // khong tra cho khi chuyen that bai

        UUID returning = returningOrder(pickup, dropoff, slotAfter(Duration.ofHours(3)));
        assertEquals(409, startReturn(dispatcher, returning, 4, Map.of("reason", "lan 2")).statusCode());
        assertUnchanged(returning, OrderStatus.RETURNING, 4, 5);
    }

    @Test void startReturnCannotBeDoneTwice() throws Exception {
        UUID id = failedOrder(pickup, dropoff, slotAfter(Duration.ofHours(2)));
        assertEquals(200, startReturn(dispatcher, id, 3, Map.of("reason", "lan 1")).statusCode());
        assertEquals(412, startReturn(dispatcher, id, 3, Map.of("reason", "retry cu")).statusCode());
        assertUnchanged(id, OrderStatus.RETURNING, 4, 5);
    }

    // ---------- COMPLETE_RETURN ----------

    @Test void pickupStationOperatorCompletesReturn() throws Exception {
        UUID id = returningOrder(pickup, dropoff, slotAfter(Duration.ofHours(2)));
        var r = completeReturn(pickupOp, id, 4);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("RETURNED", json(r).get("status").asString());
        assertEquals("\"5\"", r.headers().firstValue("ETag").orElseThrow());
        var h = history.findByOrderIdOrderByOrderVersionAsc(id);
        var last = h.getLast();
        assertEquals(6, h.size());
        assertEquals(OrderAction.COMPLETE_RETURN, last.getAction());
        assertEquals(OrderStatus.RETURNING, last.getFromStatus());
        assertEquals(OrderStatus.RETURNED, last.getToStatus());
        assertEquals(RoleName.STATION_OPERATOR, last.getActorRole());
        assertEquals(pickupOp.user().getId(), last.getActorId());
        assertEquals(0, active(id));
        assertTrue(orders.findById(id).orElseThrow().getStatus().isTerminal());
    }

    @Test void completeReturnIsLimitedToPickupStationOperator() throws Exception {
        UUID id = returningOrder(pickup, dropoff, slotAfter(Duration.ofHours(2)));
        assertEquals(404, completeReturn(dropoffOp, id, 4).statusCode());     // chi co tram nhan
        assertEquals(404, completeReturn(strangerOp, id, 4).statusCode());    // khong tram nao
        assertEquals(404, completeReturn(pickupOp, UUID.randomUUID(), 4).statusCode());
        assertEquals(403, completeReturn(dispatcher, id, 4).statusCode());
        assertEquals(403, completeReturn(customer, id, 4).statusCode());
        assertEquals(401, call("POST", "/orders/" + id + "/complete-return", null, null,
                "If-Match", v(4)).statusCode());
        assertUnchanged(id, OrderStatus.RETURNING, 4, 5);
    }

    @Test void completeReturnRejectsWrongStateAndBadVersion() throws Exception {
        UUID failed = failedOrder(pickup, dropoff, slotAfter(Duration.ofHours(2)));
        var wrong = completeReturn(pickupOp, failed, 3);
        assertEquals(409, wrong.statusCode(), wrong.body());
        assertEquals("INVALID_STATE_TRANSITION", json(wrong).get("code").asString());
        assertUnchanged(failed, OrderStatus.FAILED, 3, 4);

        UUID returning = returningOrder(pickup, dropoff, slotAfter(Duration.ofHours(3)));
        assertEquals(428, call("POST", "/orders/" + returning + "/complete-return", pickupOp.token(), null)
                .statusCode());
        assertEquals(412, completeReturn(pickupOp, returning, 3).statusCode());
        assertUnchanged(returning, OrderStatus.RETURNING, 4, 5);

        assertEquals(200, completeReturn(pickupOp, returning, 4).statusCode());
        assertEquals(409, completeReturn(pickupOp, returning, 5).statusCode()); // da RETURNED, trang thai cuoi
        assertEquals(412, completeReturn(pickupOp, returning, 4).statusCode()); // retry voi phien ban cu
        assertUnchanged(returning, OrderStatus.RETURNED, 5, 6);
    }

    @Test void fullFailureFlowLeavesNoLeakedSeats() throws Exception {
        Instant slot = slotAfter(Duration.ofHours(2));
        UUID id = scheduledOrder(tight, dropoff, slot);
        assertEquals(200, fail(dispatcher, id, 2, failBody("STATION_UNAVAILABLE", null)).statusCode());
        assertEquals(200, startReturn(dispatcher, id, 3, Map.of("reason", "Hoan ve tram gui")).statusCode());
        assertEquals(200, completeReturn(pickupOp, id, 4).statusCode());
        assertEquals(0, active(id));
        assertEquals(List.of(AttemptStatus.RELEASED),
                attempts.findByOrderIdOrderByAttemptNoAsc(id).stream().map(DeliveryAttempt::getStatus).toList());

        UUID next = seedOrder(tight, dropoff);  // tram chat con du cho cho don moi o dung slot cu
        assertEquals(200, call("POST", "/orders/" + next + "/approve", dispatcher.token(), null,
                "If-Match", v(0)).statusCode());
        assertEquals(200, schedule(dispatcher, next, 1, slot).statusCode());
    }
}
