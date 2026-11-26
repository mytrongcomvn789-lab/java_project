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

import com.smartdrone.application.ai.AiProvider;
import com.smartdrone.application.ai.DisabledAiProvider;
import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 7.1: chưa cấu hình khóa, provider DISABLED, không fallback -> AI_NOT_CONFIGURED 503 (không 500, không treo). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ai-notconf-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.jwt.key=apddmp51-ai-notconf-signing-key-over-32-characters",
        "smartdrone.ai.provider=DISABLED",
        "smartdrone.ai.fallback-enabled=false",
        "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
class AiNotConfiguredIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @LocalServerPort int port;
    @Autowired AiProvider provider;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AccessTokenIssuer tokens;

    @Test
    void notConfiguredReturns503WithStandardProblemJson() throws Exception {
        assertInstanceOf(DisabledAiProvider.class, provider);
        User u = users.saveAndFlush(new User(UUID.randomUUID() + "@ai.test", "x", "t",
                roles.findByName(RoleName.CUSTOMER).orElseThrow()));
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/ai/chat"))
                .header("Authorization", "Bearer " + tokens.issue(u)).header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hi\"}")).build();
        HttpResponse<String> r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(503, r.statusCode(), r.body());
        JsonNode body = JSON.readTree(r.body());
        assertEquals("AI_NOT_CONFIGURED", body.get("code").asString());
        assertNotNull(body.get("traceId"));
    }
}
