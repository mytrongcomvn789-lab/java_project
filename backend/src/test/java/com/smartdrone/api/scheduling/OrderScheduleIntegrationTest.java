package com.smartdrone.api.scheduling;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.scheduling.AttemptReleaseService;
import com.smartdrone.application.scheduling.OrderScheduleService;
import com.smartdrone.application.scheduling.ScheduleProperties;
import com.smartdrone.application.workflow.OrderTransitionService;
import com.smartdrone.application.workflow.TransitionCommand;
import com.smartdrone.domain.entity.*;
import com.smartdrone.domain.enums.*;
import com.smartdrone.infrastructure.persistence.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 4.2: kiem thu API len lich va xem cho trong qua HTTP that, JWT that, H2 rieng.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${SCHEDULE_TEST_DATABASE_URL:jdbc:h2:mem:schedule42;MODE=MSSQLServer;DB_CLOSE_DELAY=-1}",
    "spring.datasource.driver-class-name=${SCHEDULE_TEST_DATABASE_DRIVER:org.h2.Driver}",
    "spring.datasource.username=${SCHEDULE_TEST_DATABASE_USER:sa}",
    "spring.datasource.password=${SCHEDULE_TEST_DATABASE_PASSWORD:}",
    "app.jwt.key=schedule42-test-signing-key-longer-than-32-characters",
    "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class OrderScheduleIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
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
    @Autowired OrderScheduleService scheduler;
    @Autowired AttemptReleaseService releaser;
    @Autowired OrderTransitionService transitions;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ScheduleProperties props;

    private Actor customer, dispatcher, other;
    private Station pickup, dropoff, tight;

    record Actor(User user, String token) { }

    @BeforeEach
    void setup() {
        customer = actor(RoleName.CUSTOMER);
        dispatcher = actor(RoleName.DISPATCHER);
        other = actor(RoleName.DISPATCHER); // khong duoc phan cong tram nao
        pickup = stations.saveAndFlush(new Station("Tram gui QA", 10.7, 106.7, 3));
        dropoff = stations.saveAndFlush(new Station("Tram nhan QA", 10.8, 106.8, 3));
        tight = stations.saveAndFlush(new Station("Tram chat QA", 10.6, 106.6, 1)); // suc chua 1
        assign(dispatcher, pickup);
        assign(dispatcher, dropoff);
        assign(dispatcher, tight);
    }

    // ---------- ham tro giup ----------

    private Actor actor(RoleName role) {
        var u = users.saveAndFlush(new User(UUID.randomUUID() + "@schedule.test", "unused",
                "Schedule QA", roles.findByName(role).orElseThrow()));
        return new Actor(u, tokens.issue(u));
    }

    private StaffStationAssignment assign(Actor a, Station s) {
        return assignments.saveAndFlush(new StaffStationAssignment(a.user(), s, Instant.now()));
    }

    private UUID seedOrder(Station from, Station to) {
        var o = orders.saveAndFlush(new Order(customer.user().getId(), from.getId(), to.getId(),
                new RecipientSnapshot("Nguoi nhan QA", "0901234567", "Dia chi thu", 10.7, 106.7),
                Instant.now().minusSeconds(60)));
        history.saveAndFlush(new OrderStatusHistory(o.getId(), 0, null, OrderStatus.PENDING, null,
                customer.user().getId(), RoleName.CUSTOMER, ChangeSource.API, null, null, null, Instant.now()));
        return o.getId();
    }

    /** Tao don PENDING roi duyet qua API: tra ve don APPROVED o phien ban 1. */
    private UUID approvedOrder(Station from, Station to) throws Exception {
        UUID id = seedOrder(from, to);
        var r = call("POST", "/orders/" + id + "/approve", dispatcher.token(), null, "If-Match", "\"0\"");
        assertEquals(200, r.statusCode(), r.body());
        return id;
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

    private HttpResponse<String> schedule(Actor a, UUID id, long version, Instant slot) throws Exception {
        return call("POST", "/orders/" + id + "/schedules", a.token(),
                Map.of("slotStart", slot.toString()), "If-Match", "\"" + version + "\"");
    }

    private JsonNode json(HttpResponse<String> r) { return JSON.readTree(r.body()); }

    private long step() { return props.slotMinutes() * 60L; }

    /** Mon slot dau tien (tren luoi UTC) sau khoang "fromNow" ke tu bay gio. */
    private Instant slotAfter(Duration fromNow) {
        long t = Instant.now().plus(fromNow).getEpochSecond();
        return Instant.ofEpochSecond(((t + step() - 1) / step()) * step());
    }

    private Instant floorNow() {
        return Instant.ofEpochSecond(Instant.now().getEpochSecond() / step() * step());
    }

    private long active(UUID orderId) {
        return attempts.findByOrderIdOrderByAttemptNoAsc(orderId).stream()
                .filter(a -> a.getStatus() == AttemptStatus.ACTIVE).count();
    }

    private static Instant instant(JsonNode n) {
        return n.isNumber() ? Instant.ofEpochSecond(n.asLong()) : Instant.parse(n.asString());
    }

    private JsonNode slotView(UUID stationId, Instant slot) throws Exception {
        LocalDate day = LocalDate.ofInstant(slot, VN);
        var r = call("GET", "/stations/" + stationId + "/availability?date=" + day, dispatcher.token(), null);
        assertEquals(200, r.statusCode(), r.body());
        var slots = json(r).get("slots");
        assertEquals(24 * 60 / props.slotMinutes(), slots.size());
        for (int i = 0; i < slots.size(); i++) {
            if (instant(slots.get(i).get("slotStart")).equals(slot)) return slots.get(i);
        }
        throw new AssertionError("Khong thay slot " + slot + " trong ngay " + day);
    }

    private void assertStillApproved(UUID id) {
        var o = orders.findById(id).orElseThrow();
        assertEquals(OrderStatus.APPROVED, o.getStatus());
        assertEquals(1, o.getVersion());
        assertEquals(0, attempts.findByOrderIdOrderByAttemptNoAsc(id).size());
        assertEquals(2, history.findByOrderIdOrderByOrderVersionAsc(id).size());
    }

    /** Dua don SCHEDULED sang FAILED bang cong chuyen trang thai (4.8 chua co API). */
    private void failOrder(UUID id) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var o = orders.findById(id).orElseThrow();
            transitions.transition(o, new TransitionCommand(OrderAction.FAIL, dispatcher.user().getId(),
                    RoleName.DISPATCHER, o.getVersion(), "Thu nghiem that bai", null, null));
        });
    }

    /** Dua don sang trang thai tiep theo bang cong chuyen trang thai (khong co API tuong ung). */
    private void moveAs(UUID id, OrderAction action, RoleName role) {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var o = orders.findById(id).orElseThrow();
            transitions.transition(o, new TransitionCommand(action, dispatcher.user().getId(),
                    role, o.getVersion(), null, null, null));
        });
    }

    // ---------- cac ca kiem thu ----------

    @Test void schedulesApprovedOrderAndHoldsSeat() throws Exception {
        UUID id = approvedOrder(pickup, dropoff);
        Instant slot = slotAfter(Duration.ofHours(2));
        var r = schedule(dispatcher, id, 1, slot);
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("SCHEDULED", json(r).get("status").asString());
        assertEquals("\"2\"", r.headers().firstValue("ETag").orElseThrow());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElseThrow());

        var list = attempts.findByOrderIdOrderByAttemptNoAsc(id);
        assertEquals(1, list.size());
        var a = list.getFirst();
        assertEquals(1, a.getAttemptNo());
        assertEquals(AttemptStatus.ACTIVE, a.getStatus());
        assertEquals(pickup.getId(), a.getPickupStationId());
        assertEquals(dropoff.getId(), a.getDropoffStationId());
        assertEquals(slot, a.getSlotStart());
        assertEquals(slot.plusSeconds(step()), a.getSlotEnd());
        assertEquals(dispatcher.user().getId(), a.getCreatedBy());

        var h = history.findByOrderIdOrderByOrderVersionAsc(id);
        assertEquals(3, h.size());
        assertEquals(OrderAction.SCHEDULE, h.get(2).getAction());
        assertEquals(OrderStatus.SCHEDULED, h.get(2).getToStatus());
        assertEquals(2, h.get(2).getOrderVersion());
        assertEquals(dispatcher.user().getId(), h.get(2).getActorId());
    }

    @Test void availabilityTracksBookingsPerSlot() throws Exception {
        Instant slot = slotAfter(Duration.ofHours(2));
        var before = slotView(tight.getId(), slot);
        assertEquals(0, before.get("used").asInt());
        assertEquals(1, before.get("free").asInt());
        assertTrue(before.get("bookable").asBoolean());

        UUID id = approvedOrder(tight, dropoff);
        assertEquals(200, schedule(dispatcher, id, 1, slot).statusCode());

        var after = slotView(tight.getId(), slot);
        assertEquals(1, after.get("used").asInt());
        assertEquals(0, after.get("free").asInt());
        assertFalse(after.get("bookable").asBoolean());

        var drop = slotView(dropoff.getId(), slot); // tram nhan cung bi giu 1 cho, suc chua 3
        assertEquals(1, drop.get("used").asInt());
        assertEquals(2, drop.get("free").asInt());
        assertTrue(drop.get("bookable").asBoolean());
    }

    @Test void fullSlotIsRejectedAtEitherStationAndHoldsNothing() throws Exception {
        Instant slot = slotAfter(Duration.ofHours(2));
        UUID a = approvedOrder(tight, dropoff);
        assertEquals(200, schedule(dispatcher, a, 1, slot).statusCode());

        UUID b = approvedOrder(tight, pickup);  // tram chat la tram gui
        var r1 = schedule(dispatcher, b, 1, slot);
        assertEquals(409, r1.statusCode(), r1.body());
        assertEquals("SLOT_FULL", json(r1).get("code").asString());

        UUID c = approvedOrder(pickup, tight);  // tram chat la tram nhan
        var r2 = schedule(dispatcher, c, 1, slot);
        assertEquals(409, r2.statusCode(), r2.body());
        assertEquals("SLOT_FULL", json(r2).get("code").asString());

        assertStillApproved(b);
        assertStillApproved(c);
        // slot ke tiep van con cho
        assertEquals(200, schedule(dispatcher, b, 1, slot.plusSeconds(step())).statusCode());
    }

    @Test void inactiveStationBlocksScheduling() throws Exception {
        var inactive = Arrays.stream(StationStatus.values())
                .filter(s -> s != StationStatus.ACTIVE).findFirst().orElseThrow();
        UUID id = approvedOrder(pickup, dropoff);
        dropoff.changeStatus(inactive);
        stations.saveAndFlush(dropoff);
        Instant slot = slotAfter(Duration.ofHours(2));

        var r = schedule(dispatcher, id, 1, slot);
        assertEquals(409, r.statusCode(), r.body());
        assertEquals("STATION_UNAVAILABLE", json(r).get("code").asString());
        assertStillApproved(id);
        assertFalse(slotView(dropoff.getId(), slot).get("bookable").asBoolean());
    }

    @Test void invalidRequestsAreRejectedWithoutSideEffects() throws Exception {
        UUID id = approvedOrder(pickup, dropoff);
        Instant slot = slotAfter(Duration.ofHours(2));
        String route = "/orders/" + id + "/schedules";

        assertEquals(428, call("POST", route, dispatcher.token(), Map.of("slotStart", slot.toString())).statusCode());
        assertEquals(400, call("POST", route, dispatcher.token(), Map.of("slotStart", slot.toString()),
                "If-Match", "bad").statusCode());
        assertEquals(400, call("POST", route, dispatcher.token(), Map.of(), "If-Match", "\"1\"").statusCode());
        assertEquals(400, call("POST", route, dispatcher.token(), Map.of("slotStart", "not-a-date"),
                "If-Match", "\"1\"").statusCode());
        assertEquals(400, schedule(dispatcher, id, 1, slot.plusSeconds(step() / 3)).statusCode()); // lech moc
        assertEquals(400, schedule(dispatcher, id, 1, floorNow()).statusCode());                    // qua gan
        assertEquals(400, schedule(dispatcher, id, 1, slotAfter(Duration.ofDays(8))).statusCode()); // qua xa

        var stale = schedule(dispatcher, id, 9, slot);
        assertEquals(412, stale.statusCode());
        assertEquals("VERSION_CONFLICT", json(stale).get("code").asString());
        assertStillApproved(id);
    }

    @Test void onlyApprovedOrFailedOrdersInScopeCanBeScheduled() throws Exception {
        Instant slot = slotAfter(Duration.ofHours(2));

        UUID pending = seedOrder(pickup, dropoff);
        var wrongState = schedule(dispatcher, pending, 0, slot);
        assertEquals(409, wrongState.statusCode(), wrongState.body());
        assertEquals("INVALID_STATE_TRANSITION", json(wrongState).get("code").asString());
        assertEquals(0, attempts.findByOrderIdOrderByAttemptNoAsc(pending).size());

        UUID id = approvedOrder(pickup, dropoff);
        assertEquals(404, schedule(other, id, 1, slot).statusCode());                         // ngoai pham vi
        assertEquals(404, schedule(dispatcher, UUID.randomUUID(), 1, slot).statusCode());     // khong ton tai
        assertEquals(200, schedule(dispatcher, id, 1, slot).statusCode());
        assertEquals(409, schedule(dispatcher, id, 2, slot.plusSeconds(step())).statusCode()); // da SCHEDULED
        assertEquals(1, active(id));
    }

    @ParameterizedTest
    @EnumSource(value = RoleName.class, names = {"CUSTOMER", "SYSTEM_ADMIN", "STATION_OPERATOR", "LOGISTICS_MANAGER"})
    void otherRolesAndAnonymousCannotSchedule(RoleName role) throws Exception {
        var a = actor(role);
        assign(a, pickup);
        UUID id = approvedOrder(pickup, dropoff);
        Instant slot = slotAfter(Duration.ofHours(2));
        assertEquals(403, schedule(a, id, 1, slot).statusCode());
        assertEquals(401, call("POST", "/orders/" + id + "/schedules", null,
                Map.of("slotStart", slot.toString()), "If-Match", "\"1\"").statusCode());
        assertStillApproved(id);
    }

    @Test void reschedulingAfterFailureReleasesPreviousSeat() throws Exception {
        Instant slot = slotAfter(Duration.ofHours(2));
        UUID id = approvedOrder(tight, dropoff);
        assertEquals(200, schedule(dispatcher, id, 1, slot).statusCode()); // phien ban 2
        failOrder(id);                                                      // phien ban 3, lan thu 1 da RELEASED
        assertEquals(0, active(id));

        var r = schedule(dispatcher, id, 3, slot);                          // cung slot, tram chi con 1 cho
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("SCHEDULED", json(r).get("status").asString());

        var list = attempts.findByOrderIdOrderByAttemptNoAsc(id);
        assertEquals(2, list.size());
        assertEquals(AttemptStatus.RELEASED, list.get(0).getStatus());
        assertNotNull(list.get(0).getReleasedAt());
        assertEquals(AttemptStatus.ACTIVE, list.get(1).getStatus());
        assertEquals(2, list.get(1).getAttemptNo());
    }

    @Test void releaseActiveFreesSeatOnlyOnce() throws Exception {
        Instant slot = slotAfter(Duration.ofHours(2));
        UUID id = approvedOrder(tight, dropoff);
        assertEquals(200, schedule(dispatcher, id, 1, slot).statusCode());

        assertThrows(IllegalTransactionStateException.class, () -> releaser.releaseActive(id)); // phai co transaction
        var tx = new TransactionTemplate(transactionManager);
        assertEquals(Boolean.TRUE, tx.execute(s -> releaser.releaseActive(id)));
        assertEquals(Boolean.FALSE, tx.execute(s -> releaser.releaseActive(id)));
        assertEquals(0, active(id));
        var view = slotView(tight.getId(), slot);
        assertEquals(0, view.get("used").asInt());
        assertEquals(1, view.get("free").asInt());
    }

    @Test @WithMockUser(roles = "DISPATCHER")
    void rollbackRemovesAttemptAndKeepsOrderApproved() throws Exception {
        UUID id = approvedOrder(pickup, dropoff);
        Instant slot = slotAfter(Duration.ofHours(2));
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            scheduler.schedule(dispatcher.user().getId(), id, 1, slot);
            assertEquals(1, active(id));
            tx.setRollbackOnly();
        });
        assertStillApproved(id);
    }

    @RepeatedTest(3)
    void twoOrdersRaceForTheLastSeatAndExactlyOneWins() throws Exception {
        Instant slot = slotAfter(Duration.ofHours(2));
        UUID a = approvedOrder(tight, dropoff);
        UUID b = approvedOrder(tight, pickup);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var fa = pool.submit(() -> { start.await(); return schedule(dispatcher, a, 1, slot); });
            var fb = pool.submit(() -> { start.await(); return schedule(dispatcher, b, 1, slot); });
            start.countDown();
            var ra = fa.get(30, TimeUnit.SECONDS);
            var rb = fb.get(30, TimeUnit.SECONDS);
            assertEquals(List.of(200, 409), Stream.of(ra, rb).map(HttpResponse::statusCode).sorted().toList(),
                    ra.body() + rb.body());
            var loser = ra.statusCode() == 409 ? ra : rb;
            assertEquals("SLOT_FULL", json(loser).get("code").asString());
        }
        assertEquals(1, active(a) + active(b));
    }

    @Test void failedOrderReleasesSeatImmediately() throws Exception {
        Instant slot = slotAfter(Duration.ofHours(2));
        UUID a = approvedOrder(tight, dropoff);                 // tight chi co 1 cho
        assertEquals(200, schedule(dispatcher, a, 1, slot).statusCode());
        assertEquals(1, slotView(tight.getId(), slot).get("used").asInt());

        failOrder(a);

        var list = attempts.findByOrderIdOrderByAttemptNoAsc(a);
        assertEquals(AttemptStatus.RELEASED, list.get(0).getStatus());
        assertNotNull(list.get(0).getReleasedAt());
        assertEquals(0, active(a));
        var view = slotView(tight.getId(), slot);
        assertEquals(0, view.get("used").asInt());
        assertEquals(1, view.get("free").asInt());

        // Don khac dat duoc dung slot vua tra
        UUID b = approvedOrder(tight, dropoff);
        assertEquals(200, schedule(dispatcher, b, 1, slot).statusCode());
    }

    @Test void rollbackOfFailKeepsSeatActive() throws Exception {
        Instant slot = slotAfter(Duration.ofHours(2));
        UUID id = approvedOrder(tight, dropoff);
        assertEquals(200, schedule(dispatcher, id, 1, slot).statusCode());

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var o = orders.findById(id).orElseThrow();
            transitions.transition(o, new TransitionCommand(OrderAction.FAIL, dispatcher.user().getId(),
                    RoleName.DISPATCHER, o.getVersion(), "Thu nghiem rollback", null, null));
            assertEquals(0, active(id));      // trong transaction: da tra cho
            tx.setRollbackOnly();             // huy toan bo
        });

        assertEquals(1, active(id));          // sau rollback: cho van duoc giu
        assertEquals(OrderStatus.SCHEDULED, orders.findById(id).orElseThrow().getStatus());
    }

    @Test void failFromInTransitReleasesSeat() throws Exception {
        Instant slot = slotAfter(Duration.ofHours(2));
        UUID id = approvedOrder(tight, dropoff);
        assertEquals(200, schedule(dispatcher, id, 1, slot).statusCode());
        moveAs(id, OrderAction.RECEIVE, RoleName.STATION_OPERATOR);   // SCHEDULED -> PREPARING
        moveAs(id, OrderAction.DISPATCH, RoleName.STATION_OPERATOR);  // PREPARING -> IN_TRANSIT
        assertEquals(1, active(id));                                  // dang di giao van giu cho

        failOrder(id);                                                // IN_TRANSIT -> FAILED

        assertEquals(0, active(id));
        assertEquals(1, slotView(tight.getId(), slot).get("free").asInt());
    }

    @Test void historyRecordsAttemptIdAndRetryUsesNewAttempt() throws Exception {
        Instant slot = slotAfter(Duration.ofHours(2));
        UUID id = approvedOrder(tight, dropoff);
        assertEquals(200, schedule(dispatcher, id, 1, slot).statusCode());  // phien ban 2
        failOrder(id);                                                      // phien ban 3
        assertEquals(200, schedule(dispatcher, id, 3, slot).statusCode());  // phien ban 4

        var list = attempts.findByOrderIdOrderByAttemptNoAsc(id);
        assertEquals(2, list.size());
        assertNotEquals(list.get(0).getId(), list.get(1).getId());

        var scheduled = history.findByOrderIdOrderByOrderVersionAsc(id).stream()
                .filter(h -> h.getAction() == OrderAction.SCHEDULE).toList();
        assertEquals(2, scheduled.size());
        assertEquals(list.get(0).getId(), scheduled.get(0).getAttemptId());
        assertEquals(list.get(1).getId(), scheduled.get(1).getAttemptId());
    }
    
    @Test void availabilityIsRestrictedByRole() throws Exception {
        String path = "/stations/" + pickup.getId() + "/availability";
        assertEquals(403, call("GET", path, customer.token(), null).statusCode());
        assertEquals(403, call("GET", path, actor(RoleName.SYSTEM_ADMIN).token(), null).statusCode());
        assertEquals(401, call("GET", path, null, null).statusCode());
        assertEquals(200, call("GET", path, dispatcher.token(), null).statusCode());
        assertEquals(404, call("GET", "/stations/" + UUID.randomUUID() + "/availability",
                dispatcher.token(), null).statusCode());
    }

    @Test void availabilityIsLimitedToAssignedStations() throws Exception {
        String pickupPath = "/stations/" + pickup.getId() + "/availability";
        String tightPath = "/stations/" + tight.getId() + "/availability";

        // Khong duoc phan cong tram nao thi khong thay tram nao
        assertEquals(404, call("GET", pickupPath, other.token(), null).statusCode());
        assertEquals(404, call("GET", pickupPath, actor(RoleName.STATION_OPERATOR).token(), null).statusCode());
        assertEquals(404, call("GET", pickupPath, actor(RoleName.LOGISTICS_MANAGER).token(), null).statusCode());

        // Duoc phan cong thi xem dung tram do
        var operator = actor(RoleName.STATION_OPERATOR);
        assign(operator, pickup);
        assertEquals(200, call("GET", pickupPath, operator.token(), null).statusCode());
        assertEquals(404, call("GET", tightPath, operator.token(), null).statusCode());
    }

    @Test void availabilityShowsPartnerStationOfOrderInScope() throws Exception {
        // Dieu phoi vien chi duoc gan tram gui; tram nhan cua don la tram khac
        var d = actor(RoleName.DISPATCHER);
        assign(d, pickup);
        String partnerPath = "/stations/" + dropoff.getId() + "/availability";
        assertEquals(404, call("GET", partnerPath, d.token(), null).statusCode()); // chua co don noi hai tram

        seedOrder(pickup, dropoff);                                                // don trong pham vi cua d
        assertEquals(200, call("GET", partnerPath, d.token(), null).statusCode()); // gio xem duoc tram nhan
        assertEquals(404, call("GET", "/stations/" + tight.getId() + "/availability",
                d.token(), null).statusCode());                                    // tram khong lien quan van bi chan
    }
}