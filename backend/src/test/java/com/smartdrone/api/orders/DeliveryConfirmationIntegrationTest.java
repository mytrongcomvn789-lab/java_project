package com.smartdrone.api.orders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.files.ObjectStorage;
import com.smartdrone.application.workflow.OrderTransitionService;
import com.smartdrone.application.workflow.TransitionCommand;
import com.smartdrone.domain.entity.DeliveryAttempt;
import com.smartdrone.domain.entity.DeliveryConfirmation;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.entity.OrderPackage;
import com.smartdrone.domain.entity.RecipientSnapshot;
import com.smartdrone.domain.entity.StaffStationAssignment;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.entity.StoredFile;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.ChallengeStatus;
import com.smartdrone.domain.enums.ConfirmationMethod;
import com.smartdrone.domain.enums.FilePurpose;
import com.smartdrone.domain.enums.FileStatus;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;
import com.smartdrone.infrastructure.persistence.DeliveryConfirmationRepository;
import com.smartdrone.infrastructure.persistence.OrderPackageRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;
import com.smartdrone.infrastructure.persistence.PickupChallengeRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.StaffStationAssignmentRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;
import com.smartdrone.infrastructure.persistence.StoredFileRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:confirm47;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
    "app.jwt.key=confirm47-test-signing-key-longer-than-32-characters",
    "app.pickup.max-failed-attempts=3",
    "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class DeliveryConfirmationIntegrationTest {
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
    @Autowired PickupChallengeRepository challenges;
    @Autowired StoredFileRepository storedFiles;
    @Autowired AccessTokenIssuer tokens;
    @Autowired OrderTransitionService transitions;
    @Autowired PlatformTransactionManager txManager;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean DeliveryConfirmationRepository confirmations;
    @MockitoBean ObjectStorage storage; // MinIO thật đã có MinioFileFlowTest; FileService thật vẫn chạy

    record Actor(User user, String token) { }
    Actor customer, dispatcher, operator, pickupOnly;
    Station pickup, dropoff;
    UUID orderId, photoId;

    private Actor actor(RoleName role) {
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@confirm.test", "unused", "Confirm QA",
                roles.findByName(role).orElseThrow()));
        return new Actor(user, tokens.issue(user));
    }

    private void assign(Actor a, Station s) {
        assignments.saveAndFlush(new StaffStationAssignment(a.user(), s, Instant.now()));
    }

    @BeforeEach void setup() {
        when(storage.stat(anyString())).thenReturn(Optional.of(new ObjectStorage.StoredObjectInfo(100)));
        customer = actor(RoleName.CUSTOMER);
        dispatcher = actor(RoleName.DISPATCHER);
        operator = actor(RoleName.STATION_OPERATOR);   // trạm NHẬN
        pickupOnly = actor(RoleName.STATION_OPERATOR); // chỉ trạm GỬI
        pickup = stations.saveAndFlush(new Station("Pickup confirm QA", 10.7, 106.7, 10));
        dropoff = stations.saveAndFlush(new Station("Dropoff confirm QA", 10.8, 106.8, 10));
        assign(operator, dropoff);
        assign(pickupOnly, pickup);
        orderId = newArrivedOrder();
        photoId = photo(operator.user().getId(), FilePurpose.CONFIRMATION_PHOTO, true);
    }

    /** Dựng đơn bằng workflow thật tới AWAITING_PICKUP (fixture, không dùng endpoint lên lịch). */
    private UUID newArrivedOrder() {
        var saved = orders.saveAndFlush(new Order(customer.user().getId(), pickup.getId(), dropoff.getId(),
                new RecipientSnapshot("Test recipient", "0901234567", "Test address", 10.7, 106.7), Instant.now()));
        UUID id = saved.getId();
        var pkg = new OrderPackage(id, Instant.now());
        pkg.update("Confirm parcel", 500, 20, 15, 10, List.of(), Instant.now());
        packages.saveAndFlush(pkg);
        new TransactionTemplate(txManager).executeWithoutResult(tx -> {
            var order = orders.findById(id).orElseThrow();
            transitions.recordCreation(order, customer.user().getId(), RoleName.CUSTOMER);
            transitions.transition(order, cmd(OrderAction.APPROVE, dispatcher, RoleName.DISPATCHER, 0, null));
            var attempt = attempts.saveAndFlush(new DeliveryAttempt(id, 1, pickup.getId(), dropoff.getId(),
                    Instant.now().plusSeconds(3600), Instant.now().plusSeconds(7200),
                    dispatcher.user().getId(), Instant.now()));
            transitions.transition(order, cmd(OrderAction.SCHEDULE, dispatcher, RoleName.DISPATCHER, 1, attempt.getId()));
            transitions.transition(order, cmd(OrderAction.RECEIVE, operator, RoleName.STATION_OPERATOR, 2, attempt.getId()));
            transitions.transition(order, cmd(OrderAction.DISPATCH, operator, RoleName.STATION_OPERATOR, 3, attempt.getId()));
            transitions.transition(order, cmd(OrderAction.ARRIVE, operator, RoleName.STATION_OPERATOR, 4, attempt.getId()));
        });
        assertEquals(OrderStatus.AWAITING_PICKUP, orders.findById(id).orElseThrow().getStatus());
        return id;
    }

    private TransitionCommand cmd(OrderAction action, Actor a, RoleName role, long version, UUID attemptId) {
        return new TransitionCommand(action, a.user().getId(), role, version, null, attemptId, null);
    }

    private UUID photo(UUID owner, FilePurpose purpose, boolean ready) {
        var f = new StoredFile(owner, "confirmation-photos/2026/11/" + UUID.randomUUID() + ".png", purpose,
                "image/png", Instant.now().plusSeconds(300), Instant.now());
        if (ready) f.markReady("image/png", 100, Instant.now());
        return storedFiles.saveAndFlush(f).getId();
    }

    private HttpResponse<String> call(String method, String path, String token, Object body, String... headers)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> r) { return JSON.readTree(r.body()); }
    private long version(UUID id) { return orders.findById(id).orElseThrow().getVersion(); }
    private JsonNode pickupCode(UUID id) throws Exception {
        var r = call("GET", "/orders/" + id + "/pickup-code", customer.token(), null);
        assertEquals(200, r.statusCode(), r.body());
        return json(r);
    }
    private Map<String, Object> otpBody(String otp) { return Map.of("otp", otp, "photoFileId", photoId); }
    private HttpResponse<String> confirm(Actor a, UUID id, long version, Map<String, Object> body) throws Exception {
        return call("POST", "/orders/" + id + "/confirmations", a.token(), body, "If-Match", "\"" + version + "\"");
    }
    private long confirmCount(UUID id) {
        return history.findByOrderIdOrderByOrderVersionAsc(id).stream()
                .filter(h -> h.getAction() == OrderAction.CONFIRM).count();
    }
    private void assertUntouched(long expectedVersion) {
        assertEquals(OrderStatus.AWAITING_PICKUP, orders.findById(orderId).orElseThrow().getStatus());
        assertEquals(expectedVersion, version(orderId));
        assertEquals(0, confirmCount(orderId));
        assertTrue(confirmations.findByOrderId(orderId).isEmpty());
    }

    @Test void customerGetsCodeAndOperatorConfirmsWithOtp() throws Exception {
        var code = pickupCode(orderId);
        String qr = code.get("qrPayload").asString();
        assertTrue(code.get("otp").asString().matches("\\d{6}"));
        assertFalse(qr.contains(orderId.toString()));
        assertEquals("no-store", call("GET", "/orders/" + orderId + "/pickup-code", customer.token(), null)
                .headers().firstValue("Cache-Control").orElse(""));

        var r = confirm(operator, orderId, version(orderId), otpBody(code.get("otp").asString()));
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        assertEquals(OrderStatus.DELIVERED, orders.findById(orderId).orElseThrow().getStatus());

        var saved = confirmations.findByOrderId(orderId).orElseThrow();
        assertEquals(ConfirmationMethod.OTP, saved.getMethod());
        assertEquals(operator.user().getId(), saved.getConfirmedBy());
        assertEquals(dropoff.getId(), saved.getStationId());
        assertEquals(ChallengeStatus.USED, challenges.findById(saved.getChallengeId()).orElseThrow().getStatus());
        assertEquals(FileStatus.ATTACHED, storedFiles.findById(photoId).orElseThrow().getStatus());
        var last = history.findByOrderIdOrderByOrderVersionAsc(orderId).getLast();
        assertEquals(OrderAction.CONFIRM, last.getAction());
        assertEquals(last.getOccurredAt(), saved.getConfirmedAt());

        var evidence = call("GET", "/orders/" + orderId + "/confirmation", customer.token(), null);
        assertEquals(200, evidence.statusCode(), evidence.body());
        assertEquals(photoId.toString(), json(evidence).get("photoFileId").asString());
    }

    @Test void operatorConfirmsWithQrToken() throws Exception {
        var r = confirm(operator, orderId, version(orderId),
                Map.of("qrToken", pickupCode(orderId).get("qrPayload").asString(), "photoFileId", photoId));
        assertEquals(200, r.statusCode(), r.body());
        assertEquals(ConfirmationMethod.QR, confirmations.findByOrderId(orderId).orElseThrow().getMethod());
    }

    @Test void wrongCodesAreCountedThenLockedAndCustomerGetsFreshCode() throws Exception {
        var code = pickupCode(orderId);
        String otp = code.get("otp").asString();
        String wrong = otp.equals("000000") ? "000001" : "000000";
        long v = version(orderId);
        for (int i = 0; i < 2; i++) {
            var r = confirm(operator, orderId, v, otpBody(wrong));
            assertEquals(422, r.statusCode(), r.body());
            assertEquals("PICKUP_CODE_INVALID", json(r).get("code").asString());
        }
        var locked = confirm(operator, orderId, v, otpBody(wrong));
        assertEquals(429, locked.statusCode(), locked.body());
        assertEquals("PICKUP_CODE_LOCKED", json(locked).get("code").asString());
        assertEquals(429, confirm(operator, orderId, v, otpBody(otp)).statusCode()); // đúng mã cũng bị khóa
        assertUntouched(v);

        var fresh = pickupCode(orderId);
        assertNotEquals(code.get("challengeId").asString(), fresh.get("challengeId").asString());
        assertEquals(200, confirm(operator, orderId, v, otpBody(fresh.get("otp").asString())).statusCode());
    }

    @Test void expiredCodeIsRejectedAndReissuedOnNextOpen() throws Exception {
        var code = pickupCode(orderId);
        jdbc.update("update pickup_challenges set expires_at = ? where order_id = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(10), orderId);
        long v = version(orderId);
        var r = confirm(operator, orderId, v, otpBody(code.get("otp").asString()));
        assertEquals(410, r.statusCode(), r.body());
        assertEquals("PICKUP_CODE_EXPIRED", json(r).get("code").asString());
        assertUntouched(v);

        var fresh = pickupCode(orderId);
        assertNotEquals(code.get("challengeId").asString(), fresh.get("challengeId").asString());
        assertEquals(200, confirm(operator, orderId, v, otpBody(fresh.get("otp").asString())).statusCode());
    }

    @Test void onlyTheDropoffOperatorOfThisOrderMayConfirm() throws Exception {
        String otp = pickupCode(orderId).get("otp").asString();
        long v = version(orderId);
        assertEquals(404, confirm(pickupOnly, orderId, v, otpBody(otp)).statusCode()); // trạm gửi, sai trạm
        assertEquals(404, confirm(actor(RoleName.STATION_OPERATOR), orderId, v, otpBody(otp)).statusCode());
        assertEquals(403, confirm(customer, orderId, v, otpBody(otp)).statusCode());
        assertEquals(403, confirm(dispatcher, orderId, v, otpBody(otp)).statusCode());
        assertEquals(412, confirm(operator, orderId, v - 1, otpBody(otp)).statusCode());
        assertEquals(428, call("POST", "/orders/" + orderId + "/confirmations", operator.token(), otpBody(otp)).statusCode());
        assertUntouched(v);
    }

    @Test void codeOfAnotherOrderIsRejected() throws Exception {
        UUID other = newArrivedOrder();
        String foreignQr = pickupCode(other).get("qrPayload").asString();
        pickupCode(orderId);
        long v = version(orderId);
        var r = confirm(operator, orderId, v, Map.of("qrToken", foreignQr, "photoFileId", photoId));
        assertEquals(422, r.statusCode(), r.body());
        assertUntouched(v);
        assertEquals(OrderStatus.AWAITING_PICKUP, orders.findById(other).orElseThrow().getStatus());
    }

    @Test void photoProblemsDoNotBurnTheCodeOrCompleteTheOrder() throws Exception {
        String otp = pickupCode(orderId).get("otp").asString();
        long v = version(orderId);

        UUID notUploaded = photo(operator.user().getId(), FilePurpose.CONFIRMATION_PHOTO, false);
        var pending = confirm(operator, orderId, v, Map.of("otp", otp, "photoFileId", notUploaded));
        assertEquals(409, pending.statusCode(), pending.body());
        assertEquals("FILE_INVALID_STATE", json(pending).get("code").asString());

        UUID foreign = photo(pickupOnly.user().getId(), FilePurpose.CONFIRMATION_PHOTO, true);
        assertEquals(404, confirm(operator, orderId, v, Map.of("otp", otp, "photoFileId", foreign)).statusCode());

        UUID wrongPurpose = photo(operator.user().getId(), FilePurpose.PACKAGE_PHOTO, true);
        assertEquals(400, confirm(operator, orderId, v, Map.of("otp", otp, "photoFileId", wrongPurpose)).statusCode());

        when(storage.stat(anyString())).thenReturn(Optional.empty()); // đã READY nhưng object không còn
        var missing = confirm(operator, orderId, v, otpBody(otp));
        assertEquals(409, missing.statusCode(), missing.body());
        assertEquals("FILE_NOT_UPLOADED", json(missing).get("code").asString());

        assertUntouched(v);
        var challenge = challenges.findFirstByOrderIdOrderBySequenceNoDesc(orderId).orElseThrow();
        assertEquals(ChallengeStatus.ACTIVE, challenge.getStatus());
        assertEquals(0, challenge.getFailedAttempts());

        when(storage.stat(anyString())).thenReturn(Optional.of(new ObjectStorage.StoredObjectInfo(100)));
        assertEquals(200, confirm(operator, orderId, v, otpBody(otp)).statusCode()); // sửa ảnh xong vẫn dùng được mã
    }

    @Test void resendingAfterSuccessReplaysInsteadOfCompletingTwice() throws Exception {
        String otp = pickupCode(orderId).get("otp").asString();
        long v = version(orderId);
        var first = confirm(operator, orderId, v, otpBody(otp));
        assertEquals(200, first.statusCode(), first.body());
        int historySize = history.findByOrderIdOrderByOrderVersionAsc(orderId).size();

        var again = confirm(operator, orderId, v, otpBody(otp));
        assertEquals(200, again.statusCode(), again.body());
        assertEquals("true", again.headers().firstValue("Idempotent-Replayed").orElse(""));
        assertEquals(json(first).get("id").asString(), json(again).get("id").asString());
        assertEquals(historySize, history.findByOrderIdOrderByOrderVersionAsc(orderId).size());
        assertEquals(1, confirmCount(orderId));

        UUID other = photo(operator.user().getId(), FilePurpose.CONFIRMATION_PHOTO, true);
        assertEquals(409, confirm(operator, orderId, v, Map.of("otp", otp, "photoFileId", other)).statusCode());
        assertEquals(404, confirm(pickupOnly, orderId, v, otpBody(otp)).statusCode());
    }

    @Test void concurrentRequestsCompleteTheOrderOnlyOnce() throws Exception {
        String otp = pickupCode(orderId).get("otp").asString();
        long v = version(orderId);
        int n = 6;
        var pool = Executors.newFixedThreadPool(n);
        var barrier = new CyclicBarrier(n);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return confirm(operator, orderId, v, otpBody(otp)).statusCode();
            }));
        }
        List<Integer> codes = new ArrayList<>();
        for (var f : futures) codes.add(f.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(codes.contains(200), codes.toString());
        assertTrue(codes.stream().allMatch(c -> c == 200 || c == 409 || c == 412), codes.toString());
        assertEquals(1, confirmCount(orderId));
        assertTrue(confirmations.findByOrderId(orderId).isPresent());
        assertEquals(OrderStatus.DELIVERED, orders.findById(orderId).orElseThrow().getStatus());
    }

    @Test void dbFailureWhileSavingEvidenceRollsEverythingBack() throws Exception {
        String otp = pickupCode(orderId).get("otp").asString();
        long v = version(orderId);
        int historySize = history.findByOrderIdOrderByOrderVersionAsc(orderId).size();

        doThrow(new IllegalStateException("db down")).when(confirmations).saveAndFlush(any(DeliveryConfirmation.class));
        var r = confirm(operator, orderId, v, otpBody(otp));
        assertEquals(500, r.statusCode(), r.body());
        assertFalse(r.body().contains(otp));

        reset(confirmations); // bỏ giả lập để các lần đọc và gọi lại dùng hàm thật
        assertUntouched(v);
        assertEquals(historySize, history.findByOrderIdOrderByOrderVersionAsc(orderId).size());
        assertEquals(ChallengeStatus.ACTIVE,
                challenges.findFirstByOrderIdOrderBySequenceNoDesc(orderId).orElseThrow().getStatus());
        assertEquals(FileStatus.READY, storedFiles.findById(photoId).orElseThrow().getStatus());

        assertEquals(200, confirm(operator, orderId, v, otpBody(otp)).statusCode());
    }

    @Test void pickupCodeIsOnlyForTheOwnerWhileAwaitingPickup() throws Exception {
        assertEquals(403, call("GET", "/orders/" + orderId + "/pickup-code", operator.token(), null).statusCode());
        assertEquals(404, call("GET", "/orders/" + orderId + "/pickup-code", actor(RoleName.CUSTOMER).token(), null).statusCode());
        var pendingOrder = orders.saveAndFlush(new Order(customer.user().getId(), pickup.getId(), dropoff.getId(),
                new RecipientSnapshot("R", "0901234567", "A", 10.7, 106.7), Instant.now()));
        assertEquals(409, call("GET", "/orders/" + pendingOrder.getId() + "/pickup-code", customer.token(), null).statusCode());
    }
}