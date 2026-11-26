package com.smartdrone.api.eta;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.domain.entity.DeliveryAttempt;
import com.smartdrone.domain.entity.Order;
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
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.StaffStationAssignmentRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 7.2: GET /v1/ai/orders/{id}/eta chạy thật trên H2 qua HTTP. Provider AI KHÔNG được cấu hình (không khóa, fallback tắt):
 * nếu ETA vẫn trả 200 thì nó không phụ thuộc AI. Dữ liệu là fixture; vận tốc và thời gian chờ lấy từ application.yml
 * (40 km/h, 5 phút: giả định demo chưa hiệu chuẩn).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:eta-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.key=apddmp52-eta-test-signing-key-over-32-characters-long",
        "smartdrone.ai.provider=AUTO",
        "smartdrone.ai.gemini.api-key=",
        "smartdrone.ai.fallback-enabled=false",
        "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class EtaApiIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired StationRepository stations;
    @Autowired OrderRepository orders;
    @Autowired DeliveryAttemptRepository attempts;
    @Autowired OrderStatusHistoryRepository histories;
    @Autowired StaffStationAssignmentRepository assignments;
    @Autowired AccessTokenIssuer tokens;

    record Who(User user, String token) { }

    private Who who(RoleName role) {
        var u = users.saveAndFlush(new User(UUID.randomUUID() + "@eta.test", "unused-test-hash", "ETA tester",
                roles.findByName(role).orElseThrow()));
        return new Who(u, tokens.issue(u));
    }

    private Order order(Who customer, Station a, Station b, OrderStatus status) {
        var o = new Order(customer.user().getId(), a.getId(), b.getId(),
                new RecipientSnapshot("Nguyễn Văn A", "0900000000", "1 Lê Lợi, Quận 1", 10.77, 106.70), Instant.now());
        o.changeStatus(status);
        return orders.saveAndFlush(o);
    }

    private Station station(String name, double lat, double lon) {
        return stations.saveAndFlush(new Station(name, lat, lon, 4));
    }

    private HttpResponse<String> eta(UUID id, String token) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/ai/orders/" + id + "/eta"))
                .timeout(Duration.ofSeconds(30)).GET();
        if (token != null) req.header("Authorization", "Bearer " + token);
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode ok(HttpResponse<String> r) {
        assertEquals(200, r.statusCode(), r.body());
        return JSON.readTree(r.body());
    }

    @Test
    void scheduledOrderUsesSlotStartPlusTravelPlusWaitAndIsLabelledFormula() throws Exception {
        var customer = who(RoleName.CUSTOMER);
        var a = station("ETA-A", 10.77, 106.70);
        var b = station("ETA-B", 10.78, 106.71);
        var o = order(customer, a, b, OrderStatus.SCHEDULED);
        Instant slot = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        attempts.saveAndFlush(new DeliveryAttempt(o.getId(), 1, a.getId(), b.getId(), slot,
                slot.plus(30, ChronoUnit.MINUTES), UUID.randomUUID(), Instant.now()));

        var res = eta(o.getId(), customer.token());
        JsonNode body = ok(res);
        assertTrue(res.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals("FORMULA", body.path("source").asString());
        assertFalse(body.path("mock").asBoolean());
        assertFalse(body.path("degraded").asBoolean());
        assertEquals(0, body.path("usage").path("totalTokens").asInt());
        JsonNode r = body.path("result");
        assertTrue(r.path("applicable").asBoolean());
        assertTrue(r.path("computed").asBoolean());
        assertEquals("FORMULA_V1", r.path("method").asString());
        assertEquals("LOW", r.path("confidence").asString());
        // ~1.57 km / 40 km/h = 3 phút (làm tròn lên) + 5 phút chờ
        assertEquals(3, r.path("travelMinutes").asInt());
        assertEquals(5, r.path("waitMinutes").asInt());
        assertEquals(slot.plus(8, ChronoUnit.MINUTES), Instant.parse(r.path("etaAt").asString()));
        assertTrue(r.path("etaMinutes").asInt() >= 126 && r.path("etaMinutes").asInt() <= 129);
        assertFalse(r.path("computedAt").isMissingNode());
        assertTrue(r.path("limitations").toString().contains("STRAIGHT_LINE_DISTANCE"));
    }

    @Test
    void otherCustomerAndUnknownOrderGetNotFound() throws Exception {
        var owner = who(RoleName.CUSTOMER);
        var other = who(RoleName.CUSTOMER);
        var o = order(owner, station("ETA-C", 10.77, 106.70), station("ETA-D", 10.78, 106.71), OrderStatus.PREPARING);
        assertEquals(404, eta(o.getId(), other.token()).statusCode());
        assertEquals(404, eta(UUID.randomUUID(), owner.token()).statusCode());
        assertEquals(200, eta(o.getId(), owner.token()).statusCode());
    }

    @Test
    void rolesAndAuthentication() throws Exception {
        var customer = who(RoleName.CUSTOMER);
        var o = order(customer, station("ETA-E", 10.77, 106.70), station("ETA-F", 10.78, 106.71), OrderStatus.PREPARING);
        assertEquals(401, eta(o.getId(), null).statusCode());
        assertEquals(403, eta(o.getId(), who(RoleName.LOGISTICS_MANAGER).token()).statusCode());
        assertEquals(403, eta(o.getId(), who(RoleName.SYSTEM_ADMIN).token()).statusCode());
    }

    @Test
    void dispatcherSeesOnlyOrdersOfAssignedStations() throws Exception {
        var customer = who(RoleName.CUSTOMER);
        var a = station("ETA-G", 10.77, 106.70);
        var b = station("ETA-H", 10.78, 106.71);
        var o = order(customer, a, b, OrderStatus.PREPARING);
        var assigned = who(RoleName.DISPATCHER);
        var unassigned = who(RoleName.DISPATCHER);
        assignments.saveAndFlush(new StaffStationAssignment(assigned.user(), a, Instant.now()));
        assertEquals(200, eta(o.getId(), assigned.token()).statusCode());
        assertEquals(404, eta(o.getId(), unassigned.token()).statusCode());
    }

    @Test
    void terminalOrderIsNotApplicable() throws Exception {
        var customer = who(RoleName.CUSTOMER);
        var o = order(customer, station("ETA-I", 10.77, 106.70), station("ETA-J", 10.78, 106.71), OrderStatus.DELIVERED);
        JsonNode r = ok(eta(o.getId(), customer.token())).path("result");
        assertFalse(r.path("applicable").asBoolean());
        assertEquals("STATUS_NOT_APPLICABLE", r.path("reason").asString());
        assertTrue(r.path("etaAt").isNull());
        assertTrue(r.path("etaMinutes").isNull());
    }

    @Test
    void missingStationCoordinatesAreReportedNotGuessed() throws Exception {
        var customer = who(RoleName.CUSTOMER);
        var o = order(customer, station("ETA-K", 0, 0), station("ETA-L", 10.78, 106.71), OrderStatus.PREPARING);
        JsonNode r = ok(eta(o.getId(), customer.token())).path("result");
        assertTrue(r.path("applicable").asBoolean());
        assertFalse(r.path("computed").asBoolean());
        assertEquals("COORDINATES_MISSING", r.path("reason").asString());
        assertTrue(r.path("etaAt").isNull());
    }

    @Test
    void inTransitUsesDepartureFromHistoryAndNeverReturnsPastEta() throws Exception {
        var customer = who(RoleName.CUSTOMER);
        var a = station("ETA-M", 10.77, 106.70);
        var b = station("ETA-N", 10.78, 106.71);
        var o = order(customer, a, b, OrderStatus.IN_TRANSIT);
        histories.saveAndFlush(new OrderStatusHistory(o.getId(), 1, OrderStatus.PREPARING, OrderStatus.IN_TRANSIT,
                OrderAction.DISPATCH, UUID.randomUUID(), RoleName.STATION_OPERATOR, ChangeSource.API, null, null,
                "trace", Instant.now().minus(1, ChronoUnit.HOURS)));
        JsonNode r = ok(eta(o.getId(), customer.token())).path("result");
        assertTrue(r.path("computed").asBoolean());
        assertEquals(0, r.path("waitMinutes").asInt());
        assertEquals(0, r.path("etaMinutes").asInt()); // đã quá hạn 3 phút: không trả ETA quá khứ
        assertTrue(r.path("limitations").toString().contains("OVERDUE_NO_LIVE_POSITION"));
        assertFalse(Instant.parse(r.path("etaAt").asString()).isBefore(Instant.parse(r.path("computedAt").asString())));
    }
}
