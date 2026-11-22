package com.smartdrone.api.error;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * 2.4: traceId trong JSON lỗi phải trùng với header X-Trace-Id và có mặt trong log,
 * để từ traceId người dùng báo lại tra được đúng log của request đó.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:trace-test;MODE=MSSQLServer;DB_CLOSE_DELAY=-1",
    "app.jwt.key=trace-test-signing-key-longer-than-32-characters",
    "logging.file.name="
})
@ActiveProfiles("h2")
@ExtendWith(OutputCaptureExtension.class)
class TraceIdIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AccessTokenIssuer tokens;

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String customerToken() {
        var user = users.saveAndFlush(new User(UUID.randomUUID() + "@trace.test", "unused-test-hash",
                "Trace tester", roles.findByName(RoleName.CUSTOMER).orElseThrow()));
        return tokens.issue(user);
    }

    /** traceId trong body = header X-Trace-Id; trả về traceId đó. */
    private String assertSameTraceId(HttpResponse<String> response) {
        String header = response.headers().firstValue("X-Trace-Id").orElseThrow();
        String body = JSON.readTree(response.body()).get("traceId").asString();
        assertEquals(header, body, response.body());
        assertDoesNotThrow(() -> UUID.fromString(header));
        return header;
    }

    @Test
    void securityErrorBodyUsesRequestTraceId() throws Exception {
        var response = call("GET", "/addresses", null, null); // ProblemJson ở tầng filter

        assertEquals(401, response.statusCode(), response.body());
        assertSameTraceId(response);
    }

    @Test
    void controllerErrorBodyUsesRequestTraceId() throws Exception {
        String token = customerToken();

        var malformed = call("POST", "/addresses", token, "{not json"); // GlobalExceptionHandler
        assertEquals(400, malformed.statusCode(), malformed.body());
        String first = assertSameTraceId(malformed);

        var invalid = call("POST", "/addresses", token, "{}"); // lỗi validation
        assertEquals(400, invalid.statusCode(), invalid.body());
        String second = assertSameTraceId(invalid);

        assertNotEquals(first, second, "mỗi request phải có traceId riêng");
    }

    @Test
    void traceIdIsPrintedInLogLines(CapturedOutput output) throws Exception {
        var response = call("GET", "/addresses", null, null);

        String traceId = assertSameTraceId(response);
        assertTrue(output.getOut().contains("[" + traceId + "]"),
                "dòng log của request phải có [traceId] theo logging.pattern.correlation");
    }
}
