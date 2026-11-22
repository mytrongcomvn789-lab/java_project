package com.smartdrone.api.statistics;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.scheduling.ScheduleProperties;
import com.smartdrone.domain.entity.DeliveryAttempt;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.entity.OrderPackage;
import com.smartdrone.domain.entity.OrderStatusHistory;
import com.smartdrone.domain.entity.RecipientSnapshot;
import com.smartdrone.domain.entity.StaffStationAssignment;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.ChangeSource;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;
import com.smartdrone.infrastructure.persistence.OrderPackageRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.StaffStationAssignmentRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;
import com.smartdrone.infrastructure.persistence.StationStatusEventRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 5.1: API thống kê chạy thật trên H2. TOÀN BỘ dữ liệu ở đây là FIXTURE tự dựng để kiểm tra công thức;
 * nó không thay cho demo dữ liệu vận hành thật khi nghiệm thu (task 5.1 yêu cầu).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:statistics-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.key=apddmp51-statistics-test-signing-key-over-32-characters",
        "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class StatisticsIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired StationRepository stations;
    @Autowired OrderRepository orders;
    @Autowired OrderPackageRepository packages;
    @Autowired OrderStatusHistoryRepository history;
    @Autowired DeliveryAttemptRepository attempts;
    @Autowired StaffStationAssignmentRepository assignments;
    @Autowired StationStatusEventRepository events;
    @Autowired AccessTokenIssuer tokens;
    @Autowired ScheduleProperties schedule;

    record Identity(User user, String token) { }

    @BeforeEach
    void clear() {
        history.deleteAll();
        attempts.deleteAll();
        packages.deleteAll();
        orders.deleteAll();
        assignments.deleteAll();
        events.deleteAll();
        stations.deleteAll();
    }

    // ---------- hạ tầng test ----------

    private Identity identity(RoleName role) {
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@statistics.test",
                "unused-test-hash", "Statistics tester", roles.findByName(role).orElseThrow()));
        return new Identity(user, tokens.issue(user));
    }

    private Identity managerOf(Station... assigned) {
        var m = identity(RoleName.LOGISTICS_MANAGER);
        for (Station s : assigned) {
            assignments.saveAndFlush(new StaffStationAssignment(m.user(), s, Instant.now()));
        }
        return m;
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(30)).GET();
        if (token != null) request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(String path, Identity who) throws Exception {
        var r = get(path, who.token());
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body());
    }

    private Station station(String name, int capacity) {
        return stations.saveAndFlush(new Station(name, 10.77, 106.70, capacity));
    }

    private static Instant vn(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(VN).toInstant();
    }

    private UUID order(Station pickup, Station dropoff, OrderStatus status, Instant createdAt) {
        var o = new Order(UUID.randomUUID(), pickup.getId(), dropoff.getId(),
                new RecipientSnapshot("Người nhận", "0900000000", "1 Lê Lợi", 10.77, 106.70), createdAt);
        o.changeStatus(status);
        return orders.saveAndFlush(o).getId();
    }

    private long version = 0;

    private void hist(UUID orderId, OrderStatus to, OrderAction action, Instant at) {
        history.saveAndFlush(new OrderStatusHistory(orderId, ++version, null, to, action, null, null,
                ChangeSource.SYSTEM, null, null, null, at));
    }

    private DeliveryAttempt attempt(UUID orderId, int no, Station p, Station d, Instant slot, Instant createdAt,
                                    boolean active) {
        var a = new DeliveryAttempt(orderId, no, p.getId(), d.getId(), slot,
                slot.plus(schedule.slotLength()), UUID.randomUUID(), createdAt);
        if (!active) a.release(createdAt);
        return attempts.saveAndFlush(a);
    }

    private Instant alignedSlot(Instant t) {
        long step = schedule.slotLength().toSeconds();
        return Instant.ofEpochSecond(Math.floorDiv(t.getEpochSecond(), step) * step);
    }

    /**
     * Bộ dữ liệu biết trước, ngày D = 10/11/2026 (giờ Việt Nam). Manager được phân công trạm A và B; trạm C ngoài phạm vi.
     * Đơn trong khoảng D..D (theo ngày tạo): 1 PENDING, 2 DELIVERED, 3 DELIVERED có retry, 4 FAILED, 5 RETURNED
     * (hoàn xong ngày D+1), 7 SCHEDULED (tạo 23:59:59), 10 DELIVERED thiếu DISPATCH, 11 RETURNING.
     * Ngoài khoảng: 6 CANCELLED tạo đúng 00:00:00 ngày D+1, 9 tạo 23:59:59 ngày D-1. Ngoài phạm vi: 8 (trạm C).
     */
    private record Data(Station a, Station b, Station c, Identity manager) { }

    private Data seed() {
        var a = station("Trạm A", 4);
        var b = station("Trạm B", 4);
        var c = station("Trạm C", 4);
        var manager = managerOf(a, b);
        Instant slot = alignedSlot(vn("2026-11-10T09:00:00"));

        order(a, b, OrderStatus.PENDING, vn("2026-11-10T10:00:00"));                                  // 1

        var o2 = order(a, b, OrderStatus.DELIVERED, vn("2026-11-10T08:00:00"));                       // 2
        hist(o2, OrderStatus.IN_TRANSIT, OrderAction.DISPATCH, vn("2026-11-10T09:00:00"));
        hist(o2, OrderStatus.DELIVERED, OrderAction.CONFIRM, vn("2026-11-10T09:30:00"));              // 1800s
        attempt(o2, 1, a, b, slot, vn("2026-11-10T08:30:00"), false);

        var o3 = order(a, b, OrderStatus.DELIVERED, vn("2026-11-10T08:00:00"));                       // 3
        hist(o3, OrderStatus.IN_TRANSIT, OrderAction.DISPATCH, vn("2026-11-10T08:30:00"));            // lần 1 (bỏ qua)
        hist(o3, OrderStatus.FAILED, OrderAction.FAIL, vn("2026-11-10T09:00:00"));
        hist(o3, OrderStatus.IN_TRANSIT, OrderAction.DISPATCH, vn("2026-11-10T11:00:00"));            // lần cuối
        hist(o3, OrderStatus.DELIVERED, OrderAction.CONFIRM, vn("2026-11-10T11:20:00"));              // 1200s
        attempt(o3, 1, a, b, slot, vn("2026-11-10T08:10:00"), false);
        attempt(o3, 2, a, b, slot, vn("2026-11-10T10:00:00"), true);

        var o4 = order(a, b, OrderStatus.FAILED, vn("2026-11-10T08:00:00"));                          // 4
        hist(o4, OrderStatus.FAILED, OrderAction.FAIL, vn("2026-11-10T12:00:00"));
        attempt(o4, 1, a, b, slot, vn("2026-11-10T08:20:00"), false);

        var o5 = order(a, b, OrderStatus.RETURNED, vn("2026-11-10T08:00:00"));                        // 5
        hist(o5, OrderStatus.RETURNED, OrderAction.COMPLETE_RETURN, vn("2026-11-11T10:00:00"));
        attempt(o5, 1, a, b, slot, vn("2026-11-10T08:40:00"), false);

        var o6 = order(a, b, OrderStatus.CANCELLED, vn("2026-11-11T00:00:00"));                       // 6 (biên D+1)
        hist(o6, OrderStatus.CANCELLED, OrderAction.CANCEL, vn("2026-11-11T00:00:00"));

        var o7 = order(a, b, OrderStatus.SCHEDULED, vn("2026-11-10T23:59:59"));                       // 7 (biên cuối D)
        attempt(o7, 1, a, b, slot, vn("2026-11-10T23:59:59"), true);

        var o8 = order(c, c, OrderStatus.DELIVERED, vn("2026-11-10T08:00:00"));                       // 8 (ngoài phạm vi)
        hist(o8, OrderStatus.IN_TRANSIT, OrderAction.DISPATCH, vn("2026-11-10T09:00:00"));
        hist(o8, OrderStatus.DELIVERED, OrderAction.CONFIRM, vn("2026-11-10T09:10:00"));
        attempt(o8, 1, c, c, slot, vn("2026-11-10T08:00:00"), true);

        order(a, b, OrderStatus.PENDING, vn("2026-11-09T23:59:59"));                                  // 9 (biên D-1)

        var o10 = order(a, b, OrderStatus.DELIVERED, vn("2026-11-10T08:00:00"));                      // 10
        hist(o10, OrderStatus.DELIVERED, OrderAction.CONFIRM, vn("2026-11-10T12:00:00"));             // không có DISPATCH

        order(a, b, OrderStatus.RETURNING, vn("2026-11-10T08:00:00"));                                // 11
        return new Data(a, b, c, manager);
    }

    private static final String D = "from=2026-11-10&to=2026-11-10";

    // ---------- số đơn, tỷ lệ, múi giờ ----------

    @Test
    void summaryByCreatedDateCountsKnownDatasetAtTimezoneBoundaries() throws Exception {
        var d = seed();
        var s = ok("/statistics/orders/summary?" + D, d.manager());

        assertEquals("Asia/Ho_Chi_Minh", s.get("timezone").asString());
        var o = s.get("orders");
        assertEquals(8, o.get("total").asInt()); // 6 (00:00 D+1) và 9 (23:59:59 D-1) nằm ngoài; 7 (23:59:59 D) nằm trong
        assertEquals(1, o.get("pending").asInt());
        assertEquals(1, o.get("inProgress").asInt());
        assertEquals(3, o.get("delivered").asInt());
        assertEquals(1, o.get("failed").asInt());
        assertEquals(1, o.get("returning").asInt());
        assertEquals(1, o.get("returned").asInt());
        assertEquals(0, o.get("cancelled").asInt());
        assertEquals(0, o.get("rejected").asInt());

        var r = s.get("rates");
        assertEquals(6, r.get("deliverySample").asInt());
        assertEquals(0.5, r.get("successRate").asDouble(), 1e-9);
        assertEquals(2.0 / 6, r.get("returnRate").asDouble(), 1e-9);
        assertEquals(0.0, r.get("cancelRate").asDouble(), 1e-9);

        var by = s.get("byStatus");
        assertEquals(OrderStatus.values().length, by.size()); // luôn đủ 12 trạng thái
        assertEquals(3, by.get("DELIVERED").asInt());
        assertEquals(0, by.get("REJECTED").asInt());
    }

    @Test
    void endOfDayBoundaryMovesTheCancelledOrderInWhenRangeExtends() throws Exception {
        var d = seed();
        var s = ok("/statistics/orders/summary?from=2026-11-10&to=2026-11-11", d.manager());
        assertEquals(9, s.get("orders").get("total").asInt());
        assertEquals(1, s.get("orders").get("cancelled").asInt());
        assertEquals(1.0 / 9, s.get("rates").get("cancelRate").asDouble(), 1e-9);
    }

    @Test
    void summaryByCompletedDateUsesStatusHistoryNotCreatedOrUpdatedAt() throws Exception {
        var d = seed();
        // Hoàn tất ngày D: đơn 2, 3, 10 (CONFIRM). Đơn 5 hoàn xong D+1, đơn 6 hủy lúc 00:00:00 D+1 => ngoài.
        var day = ok("/statistics/orders/summary?" + D + "&dateBasis=COMPLETED", d.manager());
        assertEquals("COMPLETED", day.get("dateBasis").asString());
        assertEquals(3, day.get("orders").get("total").asInt());
        assertEquals(3, day.get("orders").get("delivered").asInt());
        assertEquals(1.0, day.get("rates").get("successRate").asDouble(), 1e-9);

        var next = ok("/statistics/orders/summary?from=2026-11-11&to=2026-11-11&dateBasis=COMPLETED", d.manager());
        assertEquals(2, next.get("orders").get("total").asInt());
        assertEquals(1, next.get("orders").get("returned").asInt());
        assertEquals(1, next.get("orders").get("cancelled").asInt());
        assertEquals(0.0, next.get("rates").get("successRate").asDouble(), 1e-9);
        assertEquals(1.0, next.get("rates").get("returnRate").asDouble(), 1e-9);
    }

    @Test
    void dailyListsEveryDayAndMatchesSummaryWithoutDoubleCounting() throws Exception {
        var d = seed();
        var days = ok("/statistics/orders/daily?from=2026-11-09&to=2026-11-12", d.manager());
        assertEquals(4, days.size()); // đủ cả ngày không có đơn
        assertEquals("2026-11-09", days.get(0).get("date").asString());
        assertEquals(1, days.get(0).get("orders").get("total").asInt());  // đơn 9
        assertEquals(8, days.get(1).get("orders").get("total").asInt());  // ngày D
        assertEquals(1, days.get(2).get("orders").get("total").asInt());  // đơn 6
        assertEquals(1, days.get(2).get("orders").get("cancelled").asInt());
        assertEquals(0, days.get(3).get("orders").get("total").asInt());

        long sum = 0;
        for (var day : days) sum += day.get("orders").get("total").asLong();
        var summary = ok("/statistics/orders/summary?from=2026-11-09&to=2026-11-12", d.manager());
        assertEquals(summary.get("orders").get("total").asLong(), sum);
    }

    // ---------- attempt và thời gian giao ----------

    @Test
    void attemptsAreSeparatedFromOrdersAndRetryIsCountedOncePerOrder() throws Exception {
        var d = seed();
        var a = ok("/statistics/orders/summary?" + D, d.manager()).get("attempts");
        // Đơn 2:1, đơn 3:2, đơn 4:1, đơn 5:1, đơn 7:1 => 6 attempt của 5 đơn; đơn 8 ngoài phạm vi không được tính.
        assertEquals(6, a.get("totalAttempts").asInt());
        assertEquals(1, a.get("retryAttempts").asInt());
        assertEquals(1, a.get("ordersWithRetry").asInt());
        assertEquals(5, a.get("ordersWithAttempt").asInt());
        assertEquals(2, a.get("activeAttempts").asInt()); // đơn 3 lần 2 và đơn 7
        assertEquals(4, a.get("releasedAttempts").asInt());
        assertEquals(1.0 / 5, a.get("retryRate").asDouble(), 1e-9);
    }

    @Test
    void deliveryTimeUsesLastDispatchToConfirmAndExcludesMissingMarks() throws Exception {
        var d = seed();
        var t = ok("/statistics/orders/summary?" + D, d.manager()).get("deliveryTime");
        assertEquals(2, t.get("count").asInt());       // đơn 2 (1800s) và đơn 3 (1200s, DISPATCH cuối)
        assertEquals(1, t.get("excludedOrders").asInt()); // đơn 10 thiếu DISPATCH
        assertEquals(1500.0, t.get("avgSeconds").asDouble(), 1e-9);
        assertEquals(1200, t.get("minSeconds").asLong());
        assertEquals(1800, t.get("maxSeconds").asLong());
    }

    // ---------- không dữ liệu / phạm vi ----------

    @Test
    void emptyRangeReturnsZerosAndNullRatesInsteadOfErrors() throws Exception {
        var d = seed();
        var s = ok("/statistics/orders/summary?from=2020-01-01&to=2020-01-31", d.manager());
        assertEquals(0, s.get("orders").get("total").asInt());
        assertTrue(s.get("rates").get("successRate").isNull());
        assertTrue(s.get("rates").get("returnRate").isNull());
        assertTrue(s.get("rates").get("cancelRate").isNull());
        assertEquals(0, s.get("attempts").get("totalAttempts").asInt());
        assertTrue(s.get("attempts").get("retryRate").isNull());
        assertEquals(0, s.get("deliveryTime").get("count").asInt());
        assertTrue(s.get("deliveryTime").get("avgSeconds").isNull());
        assertEquals(31, ok("/statistics/orders/daily?from=2020-01-01&to=2020-01-31", d.manager()).size());
    }

    @Test
    void managerOnlySeesAssignedStationsAndStationFilterCannotWidenScope() throws Exception {
        var d = seed();
        // trạm C có đơn 8 nhưng manager không được phân công => lọc theo C vẫn rỗng, không lộ dữ liệu.
        var outside = ok("/statistics/orders/summary?" + D + "&stationId=" + d.c().getId(), d.manager());
        assertEquals(0, outside.get("orders").get("total").asInt());
        var inside = ok("/statistics/orders/summary?" + D + "&stationId=" + d.a().getId(), d.manager());
        assertEquals(8, inside.get("orders").get("total").asInt());

        var other = managerOf(d.c());
        assertEquals(1, ok("/statistics/orders/summary?" + D, other).get("orders").get("total").asInt()); // chỉ đơn 8
        assertEquals(0, ok("/statistics/orders/summary?" + D, managerOf()).get("orders").get("total").asInt());
    }

    // ---------- quyền ----------

    @Test
    void onlyLogisticsManagerMayCallAndServerEnforcesIt() throws Exception {
        var d = seed();
        String[] paths = {"/statistics/orders/summary?" + D, "/statistics/orders/daily?" + D,
                "/statistics/stations/load"};
        for (String p : paths) {
            assertEquals(401, get(p, null).statusCode(), p);
            for (RoleName role : List.of(RoleName.CUSTOMER, RoleName.DISPATCHER, RoleName.STATION_OPERATOR,
                    RoleName.SYSTEM_ADMIN)) {
                assertEquals(403, get(p, identity(role).token()).statusCode(), role + " " + p);
            }
            assertEquals(200, get(p, d.manager().token()).statusCode(), p);
        }
    }

    @Test
    void invalidFiltersAreRejectedWith400() throws Exception {
        var m = seed().manager();
        assertEquals(400, get("/statistics/orders/summary?from=2026-11-11&to=2026-11-10", m.token()).statusCode());
        assertEquals(400, get("/statistics/orders/summary?from=2025-01-01&to=2026-01-02", m.token()).statusCode());
        assertEquals(400, get("/statistics/orders/summary?dateBasis=UPDATED", m.token()).statusCode());
        assertEquals(400, get("/statistics/orders/summary?from=10-11-2026", m.token()).statusCode());
        assertEquals(400, get("/statistics/orders/summary?stationId=abc", m.token()).statusCode());
    }

    // ---------- tải trạm ----------

    @Test
    void stationLoadSeparatesCapacityBookedAndProcessing() throws Exception {
        var l1 = station("Trạm L1", 4);
        var l2 = station("Trạm L2", 2);
        var l3 = station("Trạm L3", 5); // ngoài phạm vi của manager chính
        var manager = managerOf(l1);
        var both = managerOf(l1, l2);
        Instant slot = alignedSlot(vn("2026-11-10T09:00:00"));
        Instant now = Instant.now();

        var prep = order(l1, l2, OrderStatus.PREPARING, now);          // đang xử lý ở trạm GỬI L1
        var wait = order(l2, l1, OrderStatus.AWAITING_PICKUP, now);    // đang xử lý ở trạm NHẬN L1
        var sched = order(l1, l2, OrderStatus.SCHEDULED, now);         // chưa tới trạm: không tính vào processing
        for (UUID id : List.of(prep, wait, sched)) { // "đang xử lý" đếm số KIỆN (cùng nguồn 4.6) nên mỗi đơn cần 1 kiện
            var pack = new OrderPackage(id, now);
            pack.update("Gói mẫu", 500, 10, 10, 10, List.of(), now);
            packages.saveAndFlush(pack);
        }
        attempt(prep, 1, l1, l2, slot, now, true);                     // đã đặt (đếm ở cả L1 và L2)
        attempt(wait, 1, l2, l1, slot, now, false);                    // đã trả chỗ: không tính
        attempt(prep, 2, l1, l2, slot.plus(schedule.slotLength()), now, true); // khung giờ khác: không tính

        var list = ok("/statistics/stations/load?at=" + slot.plusSeconds(60), manager);
        assertEquals(1, list.size()); // chỉ L1
        var s = list.get(0);
        assertEquals(l1.getId().toString(), s.get("stationId").asString());
        assertEquals(4, s.get("capacity").asInt());
        assertEquals(1, s.get("booked").asInt());
        assertEquals(3, s.get("free").asInt());
        assertEquals(2, s.get("processing").asInt());
        assertEquals(0.25, s.get("utilization").asDouble(), 1e-9);
        assertEquals(slot.toString(), s.get("slotStart").asString());

        var two = ok("/statistics/stations/load?at=" + slot, both);
        assertEquals(2, two.size());
        var row2 = two.get(0).get("stationId").asString().equals(l2.getId().toString()) ? two.get(0) : two.get(1);
        assertEquals(2, row2.get("capacity").asInt());
        assertEquals(1, row2.get("booked").asInt());
        assertEquals(0.5, row2.get("utilization").asDouble(), 1e-9);
        assertEquals(0, row2.get("processing").asInt());

        var filtered = ok("/statistics/stations/load?stationId=" + l3.getId(), both);
        assertEquals(0, filtered.size()); // ngoài phạm vi
    }

    // ---------- thời gian đáp ứng ----------

    @Test
    void summaryAndDailyStayFastOnThousandsOfOrders() throws Exception {
        var a = station("Trạm A", 4);
        var b = station("Trạm B", 4);
        var manager = managerOf(a, b);
        var statuses = OrderStatus.values();
        List<Order> batch = new ArrayList<>();
        Instant base = vn("2026-10-01T00:00:00");
        int n = 3000;
        for (int i = 0; i < n; i++) {
            var o = new Order(UUID.randomUUID(), a.getId(), b.getId(),
                    new RecipientSnapshot("Người nhận", "0900000000", "1 Lê Lợi", 10.77, 106.70),
                    base.plusSeconds(i * 900L)); // 3000 đơn trải ~31 ngày
            o.changeStatus(statuses[i % statuses.length]);
            batch.add(o);
        }
        orders.saveAllAndFlush(batch);

        long t0 = System.nanoTime();
        var s = ok("/statistics/orders/summary?from=2026-10-01&to=2026-11-30", manager);
        var days = ok("/statistics/orders/daily?from=2026-10-01&to=2026-11-30", manager);
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertEquals(n, s.get("orders").get("total").asInt());
        long sum = 0;
        for (var day : days) sum += day.get("orders").get("total").asLong();
        assertEquals(n, sum);
        assertTrue(ms < 5000, "Hai API thống kê mất " + ms + " ms với " + n + " đơn");
    }
}
