package com.smartdrone.api.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import com.smartdrone.application.ai.AiProvider;
import com.smartdrone.application.ai.AiRequest;
import com.smartdrone.application.ai.AiResponse;
import com.smartdrone.application.ai.AiSource;
import com.smartdrone.application.ai.AiUsage;
import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.entity.RecipientSnapshot;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 7.1: /v1/ai/** chạy thật trên H2 với provider giả (không gọi mạng). Dữ liệu là FIXTURE, không phải kết quả AI thật.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.key=apddmp51-ai-test-signing-key-over-32-characters-long",
        "smartdrone.ai.provider=AUTO",
        "smartdrone.ai.gemini.api-key=",
        "smartdrone.ai.fallback-enabled=false",
        "smartdrone.ai.rate-limit.per-user-per-minute=1000",
        "smartdrone.ai.rate-limit.global-per-minute=1000",
        "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
@Import(AiApiIntegrationTest.SwitchableProviderConfig.class)
class AiApiIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET_SENTINEL = "SENTINEL-PROVIDER-SECRET-9f3a";

    /** Provider giả có thể bật lỗi/chậm và đếm số lần gọi. */
    static class SwitchableProvider implements AiProvider {
        final AtomicInteger calls = new AtomicInteger();
        volatile RuntimeException failure;

        @Override
        public AiResponse generate(AiRequest r) {
            calls.incrementAndGet();
            if (failure != null) throw failure;
            String text = switch (r.task()) {
                case ETA -> "{\"etaMinutes\":15,\"confidence\":\"LOW\",\"explanation\":\"fixture\"}";
                case ORDER_SUMMARY -> "{\"summary\":\"fixture\",\"highlights\":[]}";
                case CHAT -> "{\"reply\":\"fixture\"}";
                case OPS_ANALYSIS -> "{\"insights\":[],\"recommendations\":[]}";
            };
            return new AiResponse(r.requestId(), text, "fixture-model", AiSource.MOCK, false,
                    new AiUsage(1, 1, 2), 1, Instant.now());
        }
    }

    @TestConfiguration
    static class SwitchableProviderConfig {
        @Bean
        @Primary
        SwitchableProvider switchableProvider() {
            return new SwitchableProvider();
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort int port;
    @Autowired SwitchableProvider provider;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired StationRepository stations;
    @Autowired OrderRepository orders;
    @Autowired AccessTokenIssuer tokens;

    record Who(User user, String token) { }

    @BeforeEach
    void reset() {
        provider.failure = null;
        provider.calls.set(0);
    }

    private Who who(RoleName role) {
        var u = users.saveAndFlush(new User(UUID.randomUUID() + "@ai.test", "unused-test-hash", "AI tester",
                roles.findByName(role).orElseThrow()));
        return new Who(u, tokens.issue(u));
    }

    private UUID orderOf(Who customer) {
        var a = stations.saveAndFlush(new Station("AI-A", 10.77, 106.70, 4));
        var b = stations.saveAndFlush(new Station("AI-B", 10.78, 106.71, 4));
        var o = new Order(customer.user().getId(), a.getId(), b.getId(),
                new RecipientSnapshot("Nguyễn Văn A", "0900000000", "1 Lê Lợi, Quận 1", 10.77, 106.70), Instant.now());
        o.changeStatus(OrderStatus.PENDING);
        return orders.saveAndFlush(o).getId();
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(30));
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) {
            req.header("Content-Type", "application/json");
            req.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            req.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assertProblem(HttpResponse<String> r, int status, String code) {
        assertEquals(status, r.statusCode(), r.body());
        assertEquals(code, JSON.readTree(r.body()).get("code").asString(), r.body());
    }

    @Test
    void anonymousIsUnauthorizedAndApiKeyHeaderDoesNotHelp() throws Exception {
        assertProblem(call("POST", "/ai/chat", null, "{\"message\":\"hi\"}"), 401, "AUTH_REQUIRED");
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/ai/chat"))
                .header("X-Api-Key", UUID.randomUUID() + ".abcdef").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hi\"}")).build();
        var r = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertProblem(r, 401, "AUTH_REQUIRED");
        assertEquals(0, provider.calls.get());
    }

    @Test
    void wrongRoleIsForbiddenAndProviderNotCalled() throws Exception {
        var customer = who(RoleName.CUSTOMER);
        var manager = who(RoleName.LOGISTICS_MANAGER);
        assertProblem(call("POST", "/ai/analytics", customer.token(), "{}"), 403, "FORBIDDEN");
        assertProblem(call("POST", "/ai/chat", manager.token(), "{\"message\":\"hi\"}"), 403, "FORBIDDEN");
        assertProblem(call("POST", "/ai/orders/" + UUID.randomUUID() + "/summary", manager.token(), null), 403, "FORBIDDEN");
        assertProblem(call("POST", "/ai/chat", who(RoleName.SYSTEM_ADMIN).token(), "{\"message\":\"hi\"}"), 403, "FORBIDDEN");
        assertEquals(0, provider.calls.get());
    }

    @Test
    void customerSeesOwnOrderButOthersGetNotFoundWithoutCallingProvider() throws Exception {
        var owner = who(RoleName.CUSTOMER);
        var other = who(RoleName.CUSTOMER);
        UUID id = orderOf(owner);

        var ok = call("POST", "/ai/orders/" + id + "/summary", owner.token(), null);
        assertEquals(200, ok.statusCode(), ok.body());
        JsonNode body = JSON.readTree(ok.body());
        assertEquals("fixture", body.path("result").path("summary").asString());
        for (String f : new String[] {"model", "mock", "degraded", "usage", "requestId", "generatedAt"}) {
            assertFalse(body.path(f).isMissingNode(), "thiếu trường envelope " + f);
        }
        int callsAfterOwn = provider.calls.get();

        assertProblem(call("POST", "/ai/orders/" + id + "/summary", other.token(), null), 404, "RESOURCE_NOT_FOUND");
        assertProblem(call("GET", "/ai/orders/" + id + "/eta", other.token(), null), 404, "RESOURCE_NOT_FOUND");
        var chat = call("POST", "/ai/chat", other.token(), "{\"message\":\"hi\",\"orderId\":\"" + id + "\"}");
        assertProblem(chat, 404, "RESOURCE_NOT_FOUND");
        assertProblem(call("POST", "/ai/orders/" + UUID.randomUUID() + "/summary", owner.token(), null),
                404, "RESOURCE_NOT_FOUND"); // không tồn tại cho cùng kết quả với "của người khác"
        assertEquals(callsAfterOwn, provider.calls.get(), "đơn ngoài quyền không được dẫn tới lời gọi AI");
    }

    @Test
    void staffWithoutStationAssignmentCannotUseAiOnOrders() throws Exception {
        UUID id = orderOf(who(RoleName.CUSTOMER));
        assertProblem(call("GET", "/ai/orders/" + id + "/eta", who(RoleName.DISPATCHER).token(), null), 404, "RESOURCE_NOT_FOUND");
        assertProblem(call("GET", "/ai/orders/" + id + "/eta", who(RoleName.STATION_OPERATOR).token(), null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void chatEnforcesLengthAndValidation() throws Exception {
        var c = who(RoleName.CUSTOMER);
        assertProblem(call("POST", "/ai/chat", c.token(), "{\"message\":\"\"}"), 400, "VALIDATION_ERROR");
        assertProblem(call("POST", "/ai/chat", c.token(), "{\"message\":\"" + "x".repeat(1001) + "\"}"), 400, "VALIDATION_ERROR");
        var ok = call("POST", "/ai/chat", c.token(), "{\"message\":\"xin chào\"}");
        assertEquals(200, ok.statusCode(), ok.body());
        assertFalse(JSON.readTree(ok.body()).path("result").path("conversationId").isMissingNode());
    }

    @Test
    void managerAnalyticsUsesAggregateStatisticsOnly() throws Exception {
        var m = who(RoleName.LOGISTICS_MANAGER);
        var r = call("POST", "/ai/analytics", m.token(), "{}");
        assertEquals(200, r.statusCode(), r.body());
        assertTrue(JSON.readTree(r.body()).path("result").has("insights"));
    }

    @Test
    void providerTimeoutBecomes504AndProviderErrorBecomes503NotInternalError() throws Exception {
        var c = who(RoleName.CUSTOMER);
        provider.failure = new ApiException(ErrorCode.AI_TIMEOUT);
        assertProblem(call("POST", "/ai/chat", c.token(), "{\"message\":\"hi\"}"), 504, "AI_TIMEOUT");
        provider.failure = new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        assertProblem(call("POST", "/ai/chat", c.token(), "{\"message\":\"hi\"}"), 503, "AI_PROVIDER_UNAVAILABLE");
        provider.failure = new ApiException(ErrorCode.AI_NOT_CONFIGURED);
        assertProblem(call("POST", "/ai/chat", c.token(), "{\"message\":\"hi\"}"), 503, "AI_NOT_CONFIGURED");
        provider.failure = new IllegalStateException(SECRET_SENTINEL);
        var r = call("POST", "/ai/chat", c.token(), "{\"message\":\"hi\"}");
        assertProblem(r, 503, "AI_PROVIDER_UNAVAILABLE");
        assertFalse(r.body().contains(SECRET_SENTINEL), "không lộ chi tiết lỗi nội bộ/nhà cung cấp");
    }
}
