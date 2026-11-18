package com.smartdrone.api.realtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.auth.JwtProperties;
import com.smartdrone.application.files.FileService;
import com.smartdrone.application.realtime.OrderChannel;
import com.smartdrone.application.stations.StaffStationScope;
import com.smartdrone.application.workflow.OrderTransitionService;
import com.smartdrone.application.workflow.TransitionCommand;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.entity.RecipientSnapshot;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Task 4.3: kết nối, phân quyền kênh theo đơn, publish sau commit, nhiều tab, token hết hạn. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:realtime-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "app.jwt.key=realtime-test-signing-key-longer-than-32-characters",
    "app.realtime.expiry-sweep-interval=PT0.5S",
    "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class OrderRealtimeIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AccessTokenIssuer tokens;
    @Autowired OrderRepository orders;
    @Autowired OrderTransitionService transitions;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JwtEncoder encoder;
    @Autowired JwtProperties jwtProperties;
    @MockitoBean StaffStationScope staffScope; // giả lập phân công trạm (bản thật do người làm trạm cài)
    @MockitoBean FileService files;            // MinIO không thuộc phạm vi 4.3

    record Actor(User user, String token) {
        UUID id() { return user.getId(); }
    }

    // ---------- tiện ích ----------

    private Actor actor(RoleName role) {
        User user = users.saveAndFlush(new User(UUID.randomUUID() + "@realtime.test", "unused-test-hash",
                "Realtime tester", roles.findByName(role).orElseThrow()));
        return new Actor(user, tokens.issue(user));
    }

    private Order newOrder(UUID customerId, UUID pickup, UUID dropoff) {
        return orders.saveAndFlush(new Order(customerId, pickup, dropoff,
                new RecipientSnapshot("Nguyễn An", "0901234567", "10 Nguyễn Huệ, TP.HCM", 10.7769, 106.7009),
                Instant.now()));
    }

    private String mint(User user, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer()).audience(List.of(jwtProperties.audience()))
                .subject(user.getId().toString()).issuedAt(issuedAt).expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("email", user.getEmail()).claim("role", user.getRole().getName().name())
                .claim("accessVersion", user.getAccessVersion()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private HttpResponse<String> rest(String method, String path, String token, String ifMatch) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(30)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (ifMatch != null) request.header("If-Match", ifMatch);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void cancelOk(Order order, Actor owner, long version) throws Exception {
        var response = rest("POST", "/orders/" + order.getId() + "/cancel", owner.token(), "\"" + version + "\"");
        assertEquals(200, response.statusCode(), response.body());
    }

    /** Client STOMP chấp nhận mọi content-type để đọc JSON dạng chuỗi. */
    private static final class LenientStringConverter extends StringMessageConverter {
        @Override
        protected boolean supportsMimeType(MessageHeaders headers) { return true; }
    }

    private static final class Probe extends StompSessionHandlerAdapter {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch lost = new CountDownLatch(1);
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        volatile StompSession session;

        @Override public Type getPayloadType(StompHeaders headers) { return String.class; }

        @Override public void afterConnected(StompSession session, StompHeaders headers) {
            this.session = session;
            session.setAutoReceipt(true);
            connected.countDown();
        }

        @Override public void handleFrame(StompHeaders headers, Object payload) { // khung ERROR từ server
            String message = headers.getFirst("message");
            errors.add(message == null ? "" : message);
        }

        @Override public void handleTransportError(StompSession session, Throwable exception) { lost.countDown(); }

        /** Đăng ký kênh đơn; latch mở khi server trả RECEIPT. */
        CountDownLatch subscribe(UUID orderId) {
            CountDownLatch receipt = new CountDownLatch(1);
            StompSession.Subscription sub = session.subscribe(OrderChannel.destination(orderId),
                    new StompFrameHandler() {
                        @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
                        @Override public void handleFrame(StompHeaders headers, Object payload) {
                            events.add((String) payload);
                        }
                    });
            sub.addReceiptTask(receipt::countDown);
            return receipt;
        }
    }

    private Probe connect(String token) {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new LenientStringConverter());
        client.setDefaultHeartbeat(new long[] {0, 0});
        client.setTaskScheduler(scheduler);
        client.start();
        clients.add(client);
        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) connectHeaders.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        Probe probe = new Probe();
        client.connectAsync("ws://localhost:" + port + "/api/ws", new WebSocketHttpHeaders(), connectHeaders, probe);
        return probe;
    }

    private void connected(Probe probe) throws Exception {
        assertTrue(probe.connected.await(5, TimeUnit.SECONDS), "Kết nối STOMP thất bại: " + probe.errors);
    }

    private void subscribeOk(Probe probe, UUID orderId) throws Exception {
        assertTrue(probe.subscribe(orderId).await(5, TimeUnit.SECONDS), "Không nhận RECEIPT: " + probe.errors);
        Thread.sleep(300); // broker xử lý SUBSCRIBE bất đồng bộ ngay sau RECEIPT
    }

    private void assertRejectedWith(Probe probe, String code) throws Exception {
        String error = probe.errors.poll(5, TimeUnit.SECONDS);
        assertNotNull(error, "Server phải trả khung ERROR " + code);
        assertTrue(error.contains(code), "Mong đợi " + code + " nhưng nhận: " + error);
    }

    @BeforeEach
    void startScheduler() {
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("ws-test-");
        scheduler.setDaemon(true);
        scheduler.initialize();
    }

    @AfterEach
    void stopClients() {
        clients.forEach(WebSocketStompClient::stop);
        scheduler.shutdown();
        clients.clear();
    }

    // ---------- Xác thực lúc kết nối ----------

    @Test
    void connectWithoutTokenIsRejected() throws Exception {
        Probe probe = connect(null);
        assertRejectedWith(probe, "AUTH_REQUIRED");
        assertFalse(probe.connected.await(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void connectWithGarbageTokenIsRejected() throws Exception {
        Probe probe = connect("not-a-jwt");
        assertRejectedWith(probe, "AUTH_REQUIRED");
    }

    @Test
    void connectWithExpiredTokenIsRejected() throws Exception {
        Actor customer = actor(RoleName.CUSTOMER);
        String expired = mint(customer.user(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(600));
        Probe probe = connect(expired);
        assertRejectedWith(probe, "TOKEN_EXPIRED");
    }

    @Test
    void connectionIsClosedByServerWhenTokenExpires() throws Exception {
        Actor customer = actor(RoleName.CUSTOMER);
        Probe probe = connect(mint(customer.user(), Instant.now(), Instant.now().plusSeconds(3)));
        connected(probe);
        assertTrue(probe.lost.await(10, TimeUnit.SECONDS), "Server phải đóng kết nối khi access token hết hạn");
    }

    // ---------- Phân quyền theo đơn ----------

    @Test
    void ownerReceivesEventAfterCommitAndEventIdMatchesRestHistory() throws Exception {
        Actor owner = actor(RoleName.CUSTOMER);
        Order order = newOrder(owner.id(), UUID.randomUUID(), UUID.randomUUID());
        Probe probe = connect(owner.token());
        connected(probe);
        subscribeOk(probe, order.getId());

        cancelOk(order, owner, 0);

        String body = probe.events.poll(5, TimeUnit.SECONDS);
        assertNotNull(body, "Chủ đơn phải nhận sự kiện sau khi hủy đơn");
        JsonNode event = JSON.readTree(body);
        assertEquals(1, event.get("schemaVersion").asInt());
        assertEquals("ORDER_STATUS_CHANGED", event.get("type").asString());
        assertEquals("ORDER_CANCELLED", event.get("event").asString());
        assertEquals(order.getId().toString(), event.get("orderId").asString());
        assertEquals("PENDING", event.get("fromStatus").asString());
        assertEquals("CANCELLED", event.get("status").asString());
        assertEquals(1L, event.get("version").asLong());
        assertNotNull(event.get("occurredAt"));

        // eventId phải khớp dòng lịch sử của REST để client loại trùng và tải bù.
        JsonNode history = JSON.readTree(rest("GET", "/orders/" + order.getId(), owner.token(), null).body())
                .get("history");
        assertEquals(event.get("eventId").asString(), history.get(history.size() - 1).get("eventId").asString());
    }

    @Test
    void customerCannotSubscribeToAnotherCustomersOrder() throws Exception {
        Actor owner = actor(RoleName.CUSTOMER);
        Actor other = actor(RoleName.CUSTOMER);
        Order order = newOrder(owner.id(), UUID.randomUUID(), UUID.randomUUID());
        Probe probe = connect(other.token());
        connected(probe);

        CountDownLatch receipt = probe.subscribe(order.getId());

        assertRejectedWith(probe, "FORBIDDEN");
        assertFalse(receipt.await(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void unknownOrderIsIndistinguishableFromForbidden() throws Exception {
        Actor customer = actor(RoleName.CUSTOMER);
        Probe probe = connect(customer.token());
        connected(probe);
        probe.subscribe(UUID.randomUUID());
        assertRejectedWith(probe, "FORBIDDEN");
    }

    @Test
    void systemAdminCannotSubscribe() throws Exception {
        Actor owner = actor(RoleName.CUSTOMER);
        Actor admin = actor(RoleName.SYSTEM_ADMIN);
        Order order = newOrder(owner.id(), UUID.randomUUID(), UUID.randomUUID());
        Probe probe = connect(admin.token());
        connected(probe);
        probe.subscribe(order.getId());
        assertRejectedWith(probe, "FORBIDDEN");
    }

    @Test
    void stationOperatorOnlySubscribesToAssignedStations() throws Exception {
        UUID pickup = UUID.randomUUID();
        Actor customer = actor(RoleName.CUSTOMER);
        Actor assigned = actor(RoleName.STATION_OPERATOR);
        Actor wrongStation = actor(RoleName.STATION_OPERATOR);
        Order order = newOrder(customer.id(), pickup, UUID.randomUUID());
        when(staffScope.stationIdsFor(assigned.id(), RoleName.STATION_OPERATOR)).thenReturn(Set.of(pickup));
        when(staffScope.stationIdsFor(wrongStation.id(), RoleName.STATION_OPERATOR))
                .thenReturn(Set.of(UUID.randomUUID()));

        Probe ok = connect(assigned.token());
        connected(ok);
        subscribeOk(ok, order.getId());

        Probe denied = connect(wrongStation.token());
        connected(denied);
        denied.subscribe(order.getId());
        assertRejectedWith(denied, "FORBIDDEN");
    }

    @Test
    void clientCannotPublishFakeStatusEvents() throws Exception {
        Actor owner = actor(RoleName.CUSTOMER);
        Actor attacker = actor(RoleName.CUSTOMER);
        Order order = newOrder(owner.id(), UUID.randomUUID(), UUID.randomUUID());
        Probe victim = connect(owner.token());
        connected(victim);
        subscribeOk(victim, order.getId());
        Probe evil = connect(attacker.token());
        connected(evil);

        evil.session.send(OrderChannel.destination(order.getId()),
                "{\"orderId\":\"" + order.getId() + "\",\"status\":\"DELIVERED\"}");

        assertRejectedWith(evil, "FORBIDDEN");
        assertNull(victim.events.poll(1500, TimeUnit.MILLISECONDS), "Sự kiện giả không được tới người theo dõi");
    }

    // ---------- Publish sau commit, nhiều tab ----------

    @Test
    void rolledBackTransactionPublishesNothing() throws Exception {
        Actor owner = actor(RoleName.CUSTOMER);
        Order order = newOrder(owner.id(), UUID.randomUUID(), UUID.randomUUID());
        Probe probe = connect(owner.token());
        connected(probe);
        subscribeOk(probe, order.getId());

        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            Order loaded = orders.findById(order.getId()).orElseThrow();
            transitions.transition(loaded, new TransitionCommand(OrderAction.CANCEL, owner.id(),
                    RoleName.CUSTOMER, loaded.getVersion(), null, null, null));
            status.setRollbackOnly();
        });

        assertNull(probe.events.poll(1500, TimeUnit.MILLISECONDS), "Rollback thì không được phát sự kiện");
        assertEquals(OrderStatus.PENDING, orders.findById(order.getId()).orElseThrow().getStatus());

        cancelOk(order, owner, 0); // hủy thật: kênh vẫn sống và nhận được sự kiện
        assertNotNull(probe.events.poll(5, TimeUnit.SECONDS));
    }

    @Test
    void twoTabsOfSameUserBothReceiveTheSameEvent() throws Exception {
        Actor owner = actor(RoleName.CUSTOMER);
        Order order = newOrder(owner.id(), UUID.randomUUID(), UUID.randomUUID());
        Probe tabOne = connect(owner.token());
        Probe tabTwo = connect(owner.token());
        connected(tabOne);
        connected(tabTwo);
        subscribeOk(tabOne, order.getId());
        subscribeOk(tabTwo, order.getId());

        cancelOk(order, owner, 0);

        String first = tabOne.events.poll(5, TimeUnit.SECONDS);
        String second = tabTwo.events.poll(5, TimeUnit.SECONDS);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(JSON.readTree(first).get("eventId").asString(), JSON.readTree(second).get("eventId").asString());
        assertNull(tabOne.events.poll(500, TimeUnit.MILLISECONDS), "Mỗi sự kiện chỉ gửi một lần cho mỗi tab");
    }
}