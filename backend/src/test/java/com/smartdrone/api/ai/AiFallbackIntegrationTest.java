package com.smartdrone.api.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.smartdrone.application.ai.AiProperties;
import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 7.1: bật fallback và khóa giả: provider Gemini trỏ vào cổng đóng -> degraded=true, không 500; khóa không lộ ra response. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-fallback-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.key=apddmp51-ai-fallback-signing-key-over-32-characters",
        "smartdrone.ai.provider=GEMINI",
        "smartdrone.ai.fallback-enabled=true",
        "smartdrone.ai.gemini.api-key=FAKE-KEY-FOR-TEST-ONLY-abc123",
        "smartdrone.ai.gemini.base-url=http://127.0.0.1:1",
        "smartdrone.ai.gemini.max-retries=0",
        "smartdrone.ai.gemini.connect-timeout=PT1S",
        "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class AiFallbackIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AccessTokenIssuer tokens;
    @Autowired AiProperties props;

    @Test
    void providerDownWithFallbackReturnsDegradedEnvelopeAndNeverLeaksKey() throws Exception {
        User u = users.saveAndFlush(new User(UUID.randomUUID() + "@ai.test", "x", "t",
                roles.findByName(RoleName.CUSTOMER).orElseThrow()));
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/ai/chat"))
                .header("Authorization", "Bearer " + tokens.issue(u)).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hi\"}")).build();
        HttpResponse<String> r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        JsonNode body = JSON.readTree(r.body());
        assertTrue(body.get("degraded").asBoolean());
        assertFalse(body.get("mock").asBoolean());
        assertEquals("FALLBACK", body.get("source").asString());
        assertTrue(body.path("result").path("reply").asString().contains("tạm thời"));
        assertFalse(body.path("result").path("conversationId").asString().isBlank());
        assertFalse(r.body().contains("FAKE-KEY"), "khóa không được xuất hiện trong response");
        assertFalse(props.toString().contains("FAKE-KEY"), "AiProperties.toString không được in khóa");
        assertFalse(props.gemini().toString().contains("FAKE-KEY"));
    }
}
