package com.smartdrone.api.controller;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.smartdrone.application.auth.AccessTokenIssuer;
import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.RoleRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// Cần MinIO thật: MINIO_TEST_ENDPOINT, MINIO_TEST_ACCESS_KEY, MINIO_TEST_SECRET_KEY (xem tài liệu).
@EnabledIfEnvironmentVariable(named = "MINIO_TEST_ENDPOINT", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.jwt.key=apddmp24-test-only-signing-key-at-least-32-characters",
        "app.storage.endpoint=${MINIO_TEST_ENDPOINT:}",
        "app.storage.public-endpoint=${MINIO_TEST_ENDPOINT:}",
        "app.storage.access-key=${MINIO_TEST_ACCESS_KEY:}",
        "app.storage.secret-key=${MINIO_TEST_SECRET_KEY:}",
        "app.storage.bucket=smartdrone-it-files",
        "app.storage.download-link-ttl=PT2S",
        "app.storage.max-file-size-bytes=1024",
        "logging.level.com.smartdrone.api.filter.RequestLoggingFilter=WARN"
})
@ActiveProfiles("h2")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MinioFileFlowTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] PNG = pngBytes(200);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<RoleName, String> tokens = new EnumMap<>(RoleName.class);

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder passwords;
    @Autowired AccessTokenIssuer issuer;

    @BeforeAll
    void actors() {
        for (RoleName role : new RoleName[] {RoleName.CUSTOMER, RoleName.STATION_OPERATOR, RoleName.DISPATCHER}) {
            tokens.put(role, issuer.issue(newUser(role)));
        }
    }

    private User newUser(RoleName role) {
        return users.save(new User("it-" + UUID.randomUUID() + "@example.test", passwords.encode("Passw0rd-test1"),
                "IT " + role, roles.findByName(role).orElseThrow()));
    }

    @Test
    void validPhotoUploadsViewsAndLinkExpires() throws Exception {
        String customer = tokens.get(RoleName.CUSTOMER);
        JsonNode ticket = ticket(customer, "image/png", PNG.length);
        UUID fileId = UUID.fromString(ticket.get("fileId").asString());

        assertEquals(204, postToMinio(ticket, "image/png", PNG).statusCode());
        var done = call("POST", "/v1/files/" + fileId + "/complete", customer, null);
        assertEquals(200, done.statusCode(), done.body());
        assertEquals("READY", JSON.readTree(done.body()).get("status").asString());
        assertFalse(done.body().contains("objectKey"), "không được lộ object key");

        var link = call("GET", "/v1/files/" + fileId + "/download-link", customer, null);
        assertEquals(200, link.statusCode(), link.body());
        String url = JSON.readTree(link.body()).get("url").asString();
        var image = raw(url);
        assertEquals(200, image.statusCode());
        assertArrayEquals(PNG, image.body());

        Thread.sleep(4000); // link hết hạn (TTL 2 giây)
        assertEquals(403, raw(url).statusCode(), "link hết hạn không còn dùng được");
    }

    @Test
    void bucketIsNotPubliclyReadable() throws Exception {
        String customer = tokens.get(RoleName.CUSTOMER);
        JsonNode ticket = ticket(customer, "image/png", PNG.length);
        postToMinio(ticket, "image/png", PNG);
        String bucketUrl = ticket.get("upload").get("url").asString();
        String key = ticket.get("upload").get("fields").get("key").asString();
        assertEquals(403, raw(bucketUrl + "/" + key).statusCode(), "đọc ẩn danh phải bị từ chối");
    }

    @Test
    void oversizedFileIsRejectedByMinioItself() throws Exception {
        JsonNode ticket = ticket(tokens.get(RoleName.CUSTOMER), "image/png", 100);
        assertEquals(400, postToMinio(ticket, "image/png", pngBytes(2000)).statusCode());
    }

    @Test
    void fakeImageIsRejectedAtCompleteAndRemoved() throws Exception {
        String customer = tokens.get(RoleName.CUSTOMER);
        JsonNode ticket = ticket(customer, "image/png", 100);
        assertEquals(204, postToMinio(ticket, "image/png", "<html>x</html>".getBytes(StandardCharsets.UTF_8)).statusCode());
        var done = call("POST", "/v1/files/" + ticket.get("fileId").asString() + "/complete", customer, null);
        assertEquals(415, done.statusCode(), done.body());
        assertEquals("FILE_TYPE_NOT_ALLOWED", JSON.readTree(done.body()).get("code").asString());
        var link = call("GET", "/v1/files/" + ticket.get("fileId").asString() + "/download-link", customer, null);
        assertEquals(409, link.statusCode());
    }

    @Test
    void missingObjectCannotBeCompleted() throws Exception {
        String customer = tokens.get(RoleName.CUSTOMER);
        JsonNode ticket = ticket(customer, "image/png", 100);
        var done = call("POST", "/v1/files/" + ticket.get("fileId").asString() + "/complete", customer, null);
        assertEquals(409, done.statusCode());
        assertEquals("FILE_NOT_UPLOADED", JSON.readTree(done.body()).get("code").asString());
    }

    @Test
    void otherUsersCannotGetLinkByChangingFileId() throws Exception {
        String customer = tokens.get(RoleName.CUSTOMER);
        JsonNode ticket = ticket(customer, "image/png", PNG.length);
        postToMinio(ticket, "image/png", PNG);
        String fileId = ticket.get("fileId").asString();
        call("POST", "/v1/files/" + fileId + "/complete", customer, null);

        var otherCustomer = issuer.issue(newUser(RoleName.CUSTOMER));
        assertEquals(404, call("GET", "/v1/files/" + fileId + "/download-link", otherCustomer, null).statusCode());
        assertEquals(404, call("GET", "/v1/files/" + fileId + "/download-link", tokens.get(RoleName.DISPATCHER), null).statusCode());
        assertEquals(404, call("POST", "/v1/files/" + fileId + "/complete", otherCustomer, null).statusCode());
    }

    @Test
    void wrongRoleOrPurposeOrTypeIsRefused() throws Exception {
        assertEquals(403, call("POST", "/v1/files/upload-tickets", tokens.get(RoleName.DISPATCHER),
                "{\"purpose\":\"PACKAGE_PHOTO\",\"contentType\":\"image/png\",\"sizeBytes\":10}").statusCode());
        assertEquals(403, call("POST", "/v1/files/upload-tickets", tokens.get(RoleName.CUSTOMER),
                "{\"purpose\":\"CONFIRMATION_PHOTO\",\"contentType\":\"image/png\",\"sizeBytes\":10}").statusCode());
        assertEquals(415, call("POST", "/v1/files/upload-tickets", tokens.get(RoleName.CUSTOMER),
                "{\"purpose\":\"PACKAGE_PHOTO\",\"contentType\":\"application/pdf\",\"sizeBytes\":10}").statusCode());
        assertEquals(413, call("POST", "/v1/files/upload-tickets", tokens.get(RoleName.CUSTOMER),
                "{\"purpose\":\"PACKAGE_PHOTO\",\"contentType\":\"image/png\",\"sizeBytes\":999999}").statusCode());
    }

    // ---- helper ----
    private JsonNode ticket(String token, String type, long size) throws Exception {
        var r = call("POST", "/v1/files/upload-tickets", token,
                "{\"purpose\":\"PACKAGE_PHOTO\",\"contentType\":\"" + type + "\",\"sizeBytes\":" + size + "}");
        assertEquals(201, r.statusCode(), r.body());
        return JSON.readTree(r.body());
    }

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api" + path)).timeout(Duration.ofSeconds(15));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json");
        return http.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> raw(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    // Gửi đúng như trình duyệt: multipart, các field server trả về trước, field "file" ở cuối.
    private HttpResponse<String> postToMinio(JsonNode ticket, String contentType, byte[] content) throws Exception {
        String boundary = "----it" + UUID.randomUUID();
        var out = new ByteArrayOutputStream();
        for (var e : ticket.get("upload").get("fields").properties()) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + e.getKey() + "\"\r\n\r\n"
                    + e.getValue().asString() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"p\"\r\nContent-Type: "
                + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(URI.create(ticket.get("upload").get("url").asString()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] pngBytes(int size) {
        byte[] b = new byte[size];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, b, 0, sig.length);
        return b;
    }
}