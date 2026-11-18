package com.smartdrone.api.notifications;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.files.FileService;
import com.smartdrone.application.notifications.NotificationDispatcher;
import com.smartdrone.application.notifications.NotificationReconciler;
import com.smartdrone.application.notifications.NotificationTemplates;
import com.smartdrone.application.realtime.NotificationChannel;
import com.smartdrone.application.realtime.OrderStatusChanged;
import com.smartdrone.application.stations.StaffStationScope;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.entity.OrderStatusHistory;
import com.smartdrone.domain.entity.RecipientSnapshot;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.CustomerNotificationRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Task 4.9: thông báo trạng thái cho khách: đúng người, không trùng, tải bù khi offline, tạo bù khi lỗi ghi. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:notification-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "app.jwt.key=notification-test-signing-key-longer-than-32-characters",
    "app.notifications.reconcile-interval=PT1H",   // test tự gọi reconcile(), job nền không được chen vào
    "app.notifications.reconcile-grace-seconds=0",
    "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class NotificationIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final List<WebSocketStompClient> clients = new ArrayList<>();
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AccessTokenIssuer tokens;
    @Autowired OrderRepository orders;
    @Autowired OrderStatusHistoryRepository history;
    @Autowired CustomerNotificationRepository notifications;
    @Autowired NotificationReconciler reconciler;
    @Autowired NotificationDispatcher dispatcher;
    @MockitoBean StaffStationScope staffScope;
    @MockitoBean FileService files;

    record Actor(User user, String token) {
        UUID id() { return user.getId(); }
    }

    // ---------- tiện ích ----------

    private Actor actor(RoleName role) {
        User user = users.saveAndFlush(new User(UUID.randomUUID() + "@notification.test", "unused-test-hash",
                "Notification tester", roles.findByName(role).orElseThrow()));
        return new Actor(user, tokens.issue(user));
    }

    private Order newOrder(UUID customerId) {
        return orders.saveAndFlush(new Order(customerId, UUID.randomUUID(), UUID.randomUUID(),
                new RecipientSnapshot("Nguyễn An", "0901234567", "10 Nguyễn Huệ, TP.HCM", 10.7769, 106.7009),
                Instant.now()));
    }

    private HttpResponse<String> rest(String method, String path, String token, String ifMatch) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(30)).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (ifMatch != null) request.header("If-Match", ifMatch);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode getJson(String path, String token) throws Exception {
        var response = rest("GET", path, token, null);
        assertEquals(200, response.statusCode(), response.body());
        return JSON.readTree(response.body());
    }

    private void cancelOk(Order order, Actor owner) throws Exception {
        var response = rest("POST", "/orders/" + order.getId() + "/cancel", owner.token(), "\"0\"");
        assertEquals(200, response.statusCode(), response.body());
    }

    private static final class LenientStringConverter extends StringMessageConverter {
        @Override
        protected boolean supportsMimeType(MessageHeaders headers) { return true; }
    }

    private static final class Probe extends StompSessionHandlerAdapter {
        final CountDownLatch connected = new CountDownLatch(1);
        final BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        final BlockingQueue<String> events = new LinkedBlockingQueue<>();
        volatile StompSession session;

        @Override public Type getPayloadType(StompHeaders headers) { return String.class; }

        @Override public void afterConnected(StompSession session, StompHeaders headers) {
            this.session = session;
            session.setAutoReceipt(true);
            connected.countDown();
        }

        @Override public void handleFrame(StompHeaders headers, Object payload) {
            String message = headers.getFirst("message");
            errors.add(message == null ? "" : message);
        }

        CountDownLatch subscribe(String destination) {
            CountDownLatch receipt = new CountDownLatch(1);
            StompSession.Subscription sub = session.subscribe(destination, new StompFrameHandler() {
                @Override public Type getPayloadType(StompHeaders headers) { return String.class; }
                @Override public void handleFrame(StompHeaders headers, Object payload) { events.add((String) payload); }
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
        StompHeaders headers = new StompHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        Probe probe = new Probe();
        client.connectAsync("ws://localhost:" + port + "/api/ws", new WebSocketHttpHeaders(), headers, probe);
        return probe;
    }

    private void connected(Probe probe) throws Exception {
        assertTrue(probe.connected.await(5, TimeUnit.SECONDS), "Kết nối STOMP thất bại: " + probe.errors);
    }

    private void subscribeOk(Probe probe, String destination) throws Exception {
        assertTrue(probe.subscribe(destination).await(5, TimeUnit.SECONDS), "Không nhận RECEIPT: " + probe.errors);
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

    // ---------- kịch bản ----------

    @Test
    void ownerGetsNotificationOverSocketAndRestAndCanMarkItRead() throws Exception {
        Actor owner = actor(RoleName.CUSTOMER);
        Order order = newOrder(owner.id());
        Probe probe = connect(owner.token());
        connected(probe);
        subscribeOk(probe, NotificationChannel.destination(owner.id()));

        cancelOk(order, owner);

        String pushed = probe.events.poll(5, TimeUnit.SECONDS);
        assertNotNull(pushed, "Chủ đơn phải nhận thông báo realtime");
        JsonNode push = JSON.readTree(pushed);
        assertEquals("NOTIFICATION_CREATED", push.get("type").asString());
        JsonNode pushedItem = push.get("notification");
        assertEquals(order.getId().toString(), pushedItem.get("orderId").asString());
        assertEquals("ORDER_CANCELLED", pushedItem.get("event").asString());
        assertEquals("CANCELLED", pushedItem.get("status").asString());
        assertEquals("/v1/orders/" + order.getId(), pushedItem.get("detailPath").asString());
        assertTrue(pushedItem.get("body").asString().contains(NotificationTemplates.shortCode(order.getId())));
        assertFalse(pushed.contains("Nguyễn An") || pushed.contains("0901234567") || pushed.contains("Nguyễn Huệ"),
                "Thông báo không được chứa dữ liệu cá nhân");
        assertTrue(pushedItem.path("readAt").isNull() || pushedItem.path("readAt").isMissingNode());

        // "Gửi được qua socket" không phải "đã đọc": REST vẫn báo chưa đọc.
        JsonNode list = getJson("/notifications", owner.token());
        assertEquals(1, list.get("total").asInt());
        assertEquals(1, list.get("unreadCount").asInt());
        JsonNode item = list.get("items").get(0);
        assertEquals(pushedItem.get("id").asString(), item.get("id").asString());
        JsonNode hist = JSON.readTree(rest("GET", "/orders/" + order.getId(), owner.token(), null).body()).get("history");
        assertEquals(hist.get(hist.size() - 1).get("eventId").asString(), item.get("eventId").asString());

        String id = item.get("id").asString();
        assertEquals(204, rest("POST", "/notifications/" + id + "/read", owner.token(), null).statusCode());
        assertEquals(204, rest("POST", "/notifications/" + id + "/read", owner.token(), null).statusCode()); // idempotent
        JsonNode after = getJson("/notifications", owner.token());
        assertEquals(0, after.get("unreadCount").asInt());
        assertFalse(after.get("items").get(0).get("readAt").isNull());
        assertEquals(0, getJson("/notifications?unreadOnly=true", owner.token()).get("total").asInt());
    }

    @Test
    void customersNeverSeeOrTouchEachOthersNotifications() throws Exception {
        Actor a = actor(RoleName.CUSTOMER);
        Actor b = actor(RoleName.CUSTOMER);
        Order orderOfB = newOrder(b.id());
        cancelOk(orderOfB, b);

        assertEquals(0, getJson("/notifications", a.token()).get("total").asInt(), "A không được thấy thông báo của B");
        String bId = getJson("/notifications", b.token()).get("items").get(0).get("id").asString();
        assertEquals(404, rest("POST", "/notifications/" + bId + "/read", a.token(), null).statusCode());
        assertEquals(1, getJson("/notifications", b.token()).get("unreadCount").asInt(), "B vẫn chưa đọc");

        Probe probeA = connect(a.token());
        connected(probeA);
        CountDownLatch receipt = probeA.subscribe(NotificationChannel.destination(b.id()));
        assertRejectedWith(probeA, "FORBIDDEN");
        assertFalse(receipt.await(500, TimeUnit.MILLISECONDS));
    }

    @Test
    void staffAndWildcardChannelsAreForbiddenAndRestIsCustomerOnly() throws Exception {
        Actor customer = actor(RoleName.CUSTOMER);
        Actor dispatcherUser = actor(RoleName.DISPATCHER);

        Probe staff = connect(dispatcherUser.token());
        connected(staff);
        staff.subscribe(NotificationChannel.destination(customer.id()));
        assertRejectedWith(staff, "FORBIDDEN");

        Probe wildcard = connect(customer.token());
        connected(wildcard);
        wildcard.subscribe("/topic/notifications/+");
        assertRejectedWith(wildcard, "FORBIDDEN");

        assertEquals(403, rest("GET", "/notifications", dispatcherUser.token(), null).statusCode());
        assertEquals(401, rest("GET", "/notifications", null, null).statusCode());
    }

    @Test
    void offlineCustomerReloadsAndReconcilerRepairsLostNotificationWithoutDuplicates() throws Exception {
        Actor owner = actor(RoleName.CUSTOMER);
        Order order = newOrder(owner.id());
        cancelOk(order, owner); // không có socket nào đang mở: khách đang offline

        // Kết nối lại chỉ cần gọi REST để tải bù.
        JsonNode item = getJson("/notifications", owner.token()).get("items").get(0);
        assertEquals(order.getId().toString(), item.get("orderId").asString());

        // Mô phỏng lỗi ghi thông báo sau commit: mất dòng thông báo nhưng lịch sử đơn còn.
        notifications.deleteById(UUID.fromString(item.get("id").asString()));
        assertEquals(0, getJson("/notifications", owner.token()).get("total").asInt());

        reconciler.reconcile();
        JsonNode repaired = getJson("/notifications", owner.token());
        assertEquals(1, repaired.get("total").asInt());
        assertEquals(item.get("eventId").asString(), repaired.get("items").get(0).get("eventId").asString());

        // Quét lại và phát lại cùng một event nhiều lần vẫn chỉ có một thông báo.
        reconciler.reconcile();
        OrderStatusHistory h = history.findByOrderIdOrderByOrderVersionAsc(order.getId()).get(0);
        var sameEvent = new OrderStatusChanged(h.getId(), order.getId(), h.getOrderVersion(),
                h.getFromStatus(), h.getToStatus(), "ORDER_CANCELLED", null, h.getOccurredAt());
        dispatcher.dispatch(sameEvent);
        dispatcher.dispatch(sameEvent);
        assertEquals(1, getJson("/notifications", owner.token()).get("total").asInt());
    }
}