package com.smartdrone.api.orders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.files.FileService;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.FilePurpose;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.domain.enums.StationStatus;
import com.smartdrone.infrastructure.persistence.IdempotencyRecordRepository;
import com.smartdrone.infrastructure.persistence.OrderPackageRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=${ORDER_TEST_DATABASE_URL:jdbc:h2:mem:order-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1}",
    "spring.datasource.driver-class-name=${ORDER_TEST_DATABASE_DRIVER:org.h2.Driver}",
    "spring.datasource.username=${ORDER_TEST_DATABASE_USER:sa}",
    "spring.datasource.password=${ORDER_TEST_DATABASE_PASSWORD:}",
    "app.jwt.key=order36-test-signing-key-longer-than-32-characters",
    "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class OrderIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private UUID pickupStationId;
    private UUID dropoffStationId;

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AccessTokenIssuer tokens;
    @Autowired OrderRepository orders;
    @Autowired OrderPackageRepository packages;
    @Autowired OrderStatusHistoryRepository history;
    @Autowired IdempotencyRecordRepository idempotency;
    @Autowired StationRepository stations;
    @MockitoBean FileService files; // MinIO không thuộc phạm vi 3.6 (đã có MinioFileFlowTest của 3.3)

    record Actor(UUID id, String token) { }

    // ---------- tiện ích ----------

    @BeforeEach
    void ensureStationsExist() {
        var existing = stations.findAll();
        pickupStationId = existing.stream().filter(s -> "Trạm gửi test".equals(s.getName()))
                .findFirst().orElseGet(() -> stations.saveAndFlush(
                        new Station("Trạm gửi test", 10.77, 106.70, 2))).getId();
        dropoffStationId = existing.stream().filter(s -> "Trạm nhận test".equals(s.getName()))
                .findFirst().orElseGet(() -> stations.saveAndFlush(
                        new Station("Trạm nhận test", 10.78, 106.71, 2))).getId();
        setStationStatus(pickupStationId, StationStatus.ACTIVE);
        setStationStatus(dropoffStationId, StationStatus.ACTIVE);
    }

    private Actor actor(RoleName role) {
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@order.test", "unused-test-hash",
                "Order tester", roles.findByName(role).orElseThrow()));
        return new Actor(user.getId(), tokens.issue(user));
    }

    private HttpResponse<String> call(String method, String path, String token, Object body, String... headers)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) { return JSON.readTree(response.body()); }

    private Map<String, Object> addressBody(String recipientName) {
        return new LinkedHashMap<>(Map.of("label", "Nhà riêng", "recipientName", recipientName,
                "phone", "0901234567", "addressLine", "10 Nguyễn Huệ, TP.HCM",
                "latitude", 10.7769, "longitude", 106.7009));
    }

    private UUID createAddress(Actor actor) throws Exception {
        var response = call("POST", "/addresses", actor.token(), addressBody("Nguyễn An"));
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(json(response).get("id").asString());
    }

    private Map<String, Object> orderBody(UUID addressId, String description, UUID... photos) {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("description", description);
        pkg.put("weightGrams", 750);
        pkg.put("lengthCm", 20.0);
        pkg.put("widthCm", 15.0);
        pkg.put("heightCm", 8.0);
        pkg.put("photoFileIds", List.of(photos));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pickupStationId", pickupStationId);
        body.put("dropoffStationId", dropoffStationId);
        body.put("recipientAddressId", addressId);
        body.put("package", pkg);
        return body;
    }

    private Map<String, Object> validBody(UUID addressId) {
        return orderBody(addressId, "Hộp A", UUID.randomUUID());
    }

    private UUID createOrder(Actor actor, Map<String, Object> body, String key) throws Exception {
        var response = call("POST", "/orders", actor.token(), body, "Idempotency-Key", key);
        assertEquals(201, response.statusCode(), response.body());
        return UUID.fromString(json(response).get("id").asString());
    }

    private List<Long> counts() {
        return List.of(orders.count(), packages.count(), history.count(), idempotency.count());
    }

    private long versionOf(UUID orderId) { return orders.findById(orderId).orElseThrow().getVersion(); }

    private void forceStatus(UUID orderId, OrderStatus status) {
        var order = orders.findById(orderId).orElseThrow();
        order.changeStatus(status);
        orders.saveAndFlush(order);
    }

    private void setStationStatus(UUID stationId, StationStatus status) {
        var station = stations.findById(stationId).orElseThrow();
        station.changeStatus(status);
        stations.saveAndFlush(station);
    }

    private static String etag(long version) { return "\"" + version + "\""; }

    private List<HttpResponse<String>> callTwice(Callable<HttpResponse<String>> request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> { start.await(); return request.call(); }));
            }
            start.countDown(); // cả hai bắn cùng lúc
            List<HttpResponse<String>> results = new ArrayList<>();
            for (var f : futures) results.add(f.get(60, TimeUnit.SECONDS));
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- Luồng chính: tạo -> sửa -> hủy ----------

    @Test
    void createUpdateCancelFlowWorksEndToEndAndWritesHistory() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        UUID address = createAddress(a);

        var created = call("POST", "/orders", a.token(), orderBody(address, "Hộp A", UUID.randomUUID()),
                "Idempotency-Key", "flow-1");
        assertEquals(201, created.statusCode(), created.body());
        assertEquals(etag(0), created.headers().firstValue("ETag").orElseThrow());
        assertTrue(created.headers().firstValue("Location").orElseThrow().contains("/api/v1/orders/"));
        var view = json(created);
        UUID id = UUID.fromString(view.get("id").asString());
        assertEquals("PENDING", view.get("status").asString());
        assertEquals(0, view.get("version").asLong());
        assertEquals("Nguyễn An", view.get("recipient").get("name").asString());
        assertEquals("Hộp A", view.get("package").get("description").asString());
        assertFalse(view.has("customerId"));

        var read = call("GET", "/orders/" + id, a.token(), null);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals(etag(0), read.headers().firstValue("ETag").orElseThrow());

        var updated = call("PUT", "/orders/" + id, a.token(), orderBody(address, "Hộp B", UUID.randomUUID()),
                "If-Match", etag(0));
        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals(etag(1), updated.headers().firstValue("ETag").orElseThrow());
        assertEquals("Hộp B", json(updated).get("package").get("description").asString());

        var cancelled = call("POST", "/orders/" + id + "/cancel", a.token(), null, "If-Match", etag(1));
        assertEquals(200, cancelled.statusCode(), cancelled.body());
        assertEquals("CANCELLED", json(cancelled).get("status").asString());
        assertEquals(2, json(cancelled).get("version").asLong());

        // Đơn đã hủy là trạng thái kết thúc: sửa hoặc hủy lại đều bị từ chối bằng lỗi nghiệp vụ 409
        for (var r : List.of(
                call("PUT", "/orders/" + id, a.token(), orderBody(address, "Hộp C", UUID.randomUUID()), "If-Match", etag(2)),
                call("POST", "/orders/" + id + "/cancel", a.token(), null, "If-Match", etag(2)))) {
            assertEquals(409, r.statusCode(), r.body());
            assertEquals("INVALID_STATE_TRANSITION", json(r).get("code").asString());
        }

        // Lịch sử: đúng 2 dòng (tạo, hủy). Sửa nội dung không đổi trạng thái nên không có dòng.
        var rows = history.findByOrderIdOrderByOrderVersionAsc(id);
        assertEquals(2, rows.size());
        assertNull(rows.get(0).getFromStatus());
        assertEquals(OrderStatus.PENDING, rows.get(0).getToStatus());
        assertEquals(0, rows.get(0).getOrderVersion());
        assertEquals(a.id(), rows.get(0).getActorId());
        assertEquals(OrderStatus.PENDING, rows.get(1).getFromStatus());
        assertEquals(OrderStatus.CANCELLED, rows.get(1).getToStatus());
        assertEquals(OrderAction.CANCEL, rows.get(1).getAction());
        assertEquals(2, rows.get(1).getOrderVersion());
        assertNotNull(rows.get(1).getTraceId());
    }

    @Test
    void addressBookChangesNeverAlterAnExistingOrdersRecipient() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        UUID address = createAddress(a);
        UUID id = createOrder(a, validBody(address), "snap-1");

        assertEquals(200, call("PUT", "/addresses/" + address, a.token(), addressBody("Người Khác")).statusCode());
        assertEquals(204, call("DELETE", "/addresses/" + address, a.token(), null).statusCode());

        var read = call("GET", "/orders/" + id, a.token(), null);
        assertEquals(200, read.statusCode(), read.body());
        assertEquals("Nguyễn An", json(read).get("recipient").get("name").asString());
    }

    @Test
    void orderCreationRejectsUnknownStationAndLeavesNothingBehind() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        UUID address = createAddress(a);
        var body = validBody(address);
        body.put("dropoffStationId", UUID.randomUUID());
        var before = counts();

        var response = call("POST", "/orders", a.token(), body, "Idempotency-Key", "station-missing-1");

        assertEquals(400, response.statusCode(), response.body());
        assertEquals("VALIDATION_ERROR", json(response).get("code").asString());
        assertEquals(before, counts());
    }

    @Test
    void orderCreationRejectsInactiveStationAndLeavesNothingBehind() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        UUID address = createAddress(a);
        var before = counts();

        setStationStatus(pickupStationId, StationStatus.INACTIVE);
        try {
            var response = call("POST", "/orders", a.token(), validBody(address),
                    "Idempotency-Key", "station-inactive-1");

            assertEquals(400, response.statusCode(), response.body());
            assertEquals("VALIDATION_ERROR", json(response).get("code").asString());
            assertEquals(before, counts());
        } finally {
            setStationStatus(pickupStationId, StationStatus.ACTIVE);
        }
    }

    @Test
    void existingOrderRemainsReadableAfterItsStationIsDeactivated() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        UUID address = createAddress(a);
        UUID id = createOrder(a, validBody(address), "station-history-1");

        setStationStatus(dropoffStationId, StationStatus.INACTIVE);
        try {
            var read = call("GET", "/orders/" + id, a.token(), null);

            assertEquals(200, read.statusCode(), read.body());
            assertEquals(id.toString(), json(read).get("id").asString());
            assertEquals(pickupStationId.toString(), json(read).get("pickupStationId").asString());
            assertEquals(dropoffStationId.toString(), json(read).get("dropoffStationId").asString());
            assertEquals(OrderStatus.PENDING.name(), json(read).get("status").asString());
        } finally {
            setStationStatus(dropoffStationId, StationStatus.ACTIVE);
        }
    }

    // ---------- Transaction: một thứ sai thì rollback tất cả ----------

    @Test
    void invalidPhotoRollsBackEverythingAndKeyIsNotBurned() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        UUID address = createAddress(a);
        UUID badPhoto = UUID.randomUUID(), goodPhoto = UUID.randomUUID();
        doThrow(new ApiException(ErrorCode.RESOURCE_NOT_FOUND)).when(files)
                .attach(any(UUID.class), eq(badPhoto), any(FilePurpose.class), anyString(), any(UUID.class));
        var before = counts();

        var failed = call("POST", "/orders", a.token(), orderBody(address, "Hộp", badPhoto),
                "Idempotency-Key", "rollback-1");
        assertEquals(404, failed.statusCode(), failed.body());
        assertEquals(before, counts()); // không đơn, không gói, không lịch sử, không bản ghi key nào còn lại

        // Key chưa bị "cháy": sửa lỗi rồi gửi lại cùng key vẫn tạo được
        var retry = call("POST", "/orders", a.token(), orderBody(address, "Hộp", goodPhoto),
                "Idempotency-Key", "rollback-1");
        assertEquals(201, retry.statusCode(), retry.body());
    }

    @Test
    void addressOfAnotherCustomerIsNotFoundAndLeavesNothingBehind() throws Exception {
        var owner = actor(RoleName.CUSTOMER);
        var attacker = actor(RoleName.CUSTOMER);
        UUID ownersAddress = createAddress(owner);
        var before = counts();

        var response = call("POST", "/orders", attacker.token(), validBody(ownersAddress), "Idempotency-Key", "steal-1");

        assertEquals(404, response.statusCode(), response.body());
        assertEquals("RESOURCE_NOT_FOUND", json(response).get("code").asString());
        assertEquals(before, counts());
    }

    // ---------- Validation ----------

    @Test
    void invalidRequestsAreRejectedAndLeaveNothingBehind() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        UUID address = createAddress(a);
        var before = counts();

        var noPackage = validBody(address); noPackage.remove("package");
        assertEquals(400, call("POST", "/orders", a.token(), noPackage, "Idempotency-Key", "v-1").statusCode());

        var withStatus = validBody(address); withStatus.put("status", "APPROVED"); // không được tự đặt trạng thái
        assertEquals(400, call("POST", "/orders", a.token(), withStatus, "Idempotency-Key", "v-2").statusCode());

        var sameStations = validBody(address); sameStations.put("dropoffStationId", sameStations.get("pickupStationId"));
        var same = call("POST", "/orders", a.token(), sameStations, "Idempotency-Key", "v-3");
        assertEquals(400, same.statusCode(), same.body());
        assertEquals("VALIDATION_ERROR", json(same).get("code").asString());

        var noKey = call("POST", "/orders", a.token(), validBody(address));
        assertEquals(400, noKey.statusCode(), noKey.body());

        assertEquals(before, counts());
    }

    // ---------- Bảo mật và phạm vi dữ liệu ----------

    @Test
    void missingTokenIs401AndWrongRoleIs403() throws Exception {
        UUID someId = UUID.randomUUID();
        assertEquals(401, call("POST", "/orders", null, Map.of(), "Idempotency-Key", "x").statusCode());
        assertEquals(401, call("GET", "/orders/" + someId, null, null).statusCode());
        assertEquals(401, call("PUT", "/orders/" + someId, null, Map.of(), "If-Match", etag(0)).statusCode());
        assertEquals(401, call("POST", "/orders/" + someId + "/cancel", null, null, "If-Match", etag(0)).statusCode());

        var dispatcher = actor(RoleName.DISPATCHER);
        assertEquals(403, call("POST", "/orders", dispatcher.token(), Map.of(), "Idempotency-Key", "x").statusCode());
        assertEquals(403, call("PUT", "/orders/" + someId, dispatcher.token(), Map.of(), "If-Match", etag(0)).statusCode());
        assertEquals(403, call("POST", "/orders/" + someId + "/cancel", dispatcher.token(), null, "If-Match", etag(0)).statusCode());
    }

    @Test
    void otherCustomerAndStaffCannotReadUpdateOrCancelSomeoneElsesOrder() throws Exception {
        var owner = actor(RoleName.CUSTOMER);
        var other = actor(RoleName.CUSTOMER);
        var dispatcher = actor(RoleName.DISPATCHER);
        UUID address = createAddress(owner);
        UUID id = createOrder(owner, validBody(address), "scope-1");

        for (String token : List.of(other.token(), dispatcher.token())) {
            var read = call("GET", "/orders/" + id, token, null);
            assertEquals(404, read.statusCode(), read.body()); // ngoài phạm vi: 404, không lộ là có tồn tại
            assertEquals("RESOURCE_NOT_FOUND", json(read).get("code").asString());
        }
        var update = call("PUT", "/orders/" + id, other.token(), validBody(createAddress(other)), "If-Match", etag(0));
        assertEquals(404, update.statusCode(), update.body());
        var cancel = call("POST", "/orders/" + id + "/cancel", other.token(), null, "If-Match", etag(0));
        assertEquals(404, cancel.statusCode(), cancel.body());

        assertEquals(OrderStatus.PENDING, orders.findById(id).orElseThrow().getStatus());
        assertEquals(0, versionOf(id));
    }

    @Test
    void approvedOrderCannotBeUpdatedOrCancelledByCustomer() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        UUID address = createAddress(a);
        UUID id = createOrder(a, validBody(address), "approved-1");
        forceStatus(id, OrderStatus.APPROVED); // giả lập điều phối viên đã duyệt
        long version = versionOf(id);

        var update = call("PUT", "/orders/" + id, a.token(), validBody(address), "If-Match", etag(version));
        assertEquals(409, update.statusCode(), update.body());
        assertEquals("INVALID_STATE_TRANSITION", json(update).get("code").asString());

        var cancel = call("POST", "/orders/" + id + "/cancel", a.token(), null, "If-Match", etag(version));
        assertEquals(409, cancel.statusCode(), cancel.body());
        assertEquals("INVALID_STATE_TRANSITION", json(cancel).get("code").asString());
        assertEquals(OrderStatus.APPROVED, orders.findById(id).orElseThrow().getStatus());
    }

    // ---------- If-Match ----------

    @Test
    void missingIfMatchIs428AndStaleIfMatchIs412AndOrderStaysUntouched() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        UUID address = createAddress(a);
        UUID id = createOrder(a, validBody(address), "ifmatch-1");

        var noHeader = call("PUT", "/orders/" + id, a.token(), validBody(address));
        assertEquals(428, noHeader.statusCode(), noHeader.body());
        assertEquals("PRECONDITION_REQUIRED", json(noHeader).get("code").asString());
        assertEquals(428, call("POST", "/orders/" + id + "/cancel", a.token(), null).statusCode());

        var stale = call("PUT", "/orders/" + id, a.token(), validBody(address), "If-Match", etag(99));
        assertEquals(412, stale.statusCode(), stale.body());
        assertEquals("VERSION_CONFLICT", json(stale).get("code").asString());
        assertEquals(412, call("POST", "/orders/" + id + "/cancel", a.token(), null, "If-Match", etag(99)).statusCode());

        assertEquals(0, versionOf(id));
        assertEquals(OrderStatus.PENDING, orders.findById(id).orElseThrow().getStatus());
    }

    // ---------- Idempotency-Key ----------

    @Test
    void sameKeyAndSameContentReturnsSameOrderWithoutCreatingASecond() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        var body = validBody(createAddress(a));
        long before = orders.count();

        var first = call("POST", "/orders", a.token(), body, "Idempotency-Key", "idem-1");
        var second = call("POST", "/orders", a.token(), body, "Idempotency-Key", "idem-1");

        assertEquals(201, first.statusCode(), first.body());
        assertEquals(200, second.statusCode(), second.body());
        assertEquals("true", second.headers().firstValue("Idempotent-Replayed").orElseThrow());
        assertEquals(json(first).get("id").asString(), json(second).get("id").asString());
        assertEquals(before + 1, orders.count());
    }

    @Test
    void sameKeyWithDifferentContentIsConflict409() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        UUID address = createAddress(a);
        var body = validBody(address);
        assertEquals(201, call("POST", "/orders", a.token(), body, "Idempotency-Key", "idem-2").statusCode());
        long before = orders.count();

        var different = call("POST", "/orders", a.token(), orderBody(address, "Gói khác", UUID.randomUUID()),
                "Idempotency-Key", "idem-2");

        assertEquals(409, different.statusCode(), different.body());
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", json(different).get("code").asString());
        assertEquals(before, orders.count());
    }

    @Test
    void sameKeyUsedByTwoDifferentCustomersCreatesTwoOrders() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        var b = actor(RoleName.CUSTOMER);
        UUID idA = createOrder(a, validBody(createAddress(a)), "shared-key");
        UUID idB = createOrder(b, validBody(createAddress(b)), "shared-key");
        assertNotEquals(idA, idB);
    }

    // ---------- Đồng thời: cần database thật (H2 không tái hiện chính xác khóa dòng) ----------

    @Test
    @EnabledIfEnvironmentVariable(named = "ORDER_TEST_DATABASE_URL", matches = ".+")
    void onlyOneOfTwoConcurrentUpdatesWithTheSameVersionSucceeds() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        UUID address = createAddress(a);
        UUID id = createOrder(a, validBody(address), "race-update-1");
        var update = orderBody(address, "Sửa đồng thời", UUID.randomUUID());

        var results = callTwice(() -> call("PUT", "/orders/" + id, a.token(), update, "If-Match", etag(0)));

        var codes = results.stream().map(HttpResponse::statusCode).sorted().toList();
        assertEquals(List.of(200, 412), codes, results.toString());
        assertEquals(1, versionOf(id));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ORDER_TEST_DATABASE_URL", matches = ".+")
    void twoConcurrentCreatesWithTheSameKeyProduceExactlyOneOrder() throws Exception {
        var a = actor(RoleName.CUSTOMER);
        var body = validBody(createAddress(a));
        long before = orders.count();

        var results = callTwice(() -> call("POST", "/orders", a.token(), body, "Idempotency-Key", "race-create-1"));

        var codes = results.stream().map(HttpResponse::statusCode).sorted().toList();
        assertEquals(List.of(200, 201), codes, results.toString());
        assertEquals(json(results.get(0)).get("id").asString(), json(results.get(1)).get("id").asString());
        assertEquals(before + 1, orders.count());
    }
}