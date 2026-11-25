package com.smartdrone.infrastructure.service;

import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClient;

import com.smartdrone.application.ai.AiProperties;
import com.smartdrone.application.ai.AiProvider;
import com.smartdrone.application.ai.AiRequest;
import com.smartdrone.application.ai.AiResponse;
import com.smartdrone.application.ai.AiSource;
import com.smartdrone.application.ai.AiUsage;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Gọi Gemini generateContent bằng RestClient (không SDK). Khóa nằm ở header x-goog-api-key, KHÔNG ở URL/query.
 * Chỉ dẫn hệ thống ở systemInstruction, dữ liệu ở contents. Retry tối đa smartdrone.ai.gemini.max-retries (<= 2),
 * chỉ với 5xx/timeout/lỗi mạng (backoff + jitter) và 429 có Retry-After hợp lý; không retry 400/401/403/404.
 * Chi tiết lỗi của Google không bao giờ được trả ra ngoài và không có nội dung prompt/khóa trong log.
 */
public class GeminiAiProvider implements AiProvider {
    private static final Logger log = LoggerFactory.getLogger(GeminiAiProvider.class);
    static final String KEY_HEADER = "x-goog-api-key";

    /** Tách để test không phải ngủ thật. */
    @FunctionalInterface
    public interface Sleeper { void sleep(Duration d) throws InterruptedException; }

    private final AiProperties.Gemini cfg;
    private final RestClient client;
    private final Clock clock;
    private final Sleeper sleeper;
    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiAiProvider(AiProperties.Gemini cfg, Clock clock) {
        this(cfg, clock, d -> Thread.sleep(d.toMillis()));
    }

    GeminiAiProvider(AiProperties.Gemini cfg, Clock clock, Sleeper sleeper) {
        this.cfg = cfg;
        this.clock = clock;
        this.sleeper = sleeper;
        HttpClient http = HttpClient.newBuilder().connectTimeout(cfg.connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(cfg.readTimeout());
        this.client = RestClient.builder().requestFactory(factory).baseUrl(trimSlash(cfg.baseUrl())).build();
    }

    @Override
    public AiResponse generate(AiRequest request) {
        if (!cfg.hasApiKey()) throw new ApiException(ErrorCode.AI_NOT_CONFIGURED);
        String body = buildBody(request);
        String path = "/" + cfg.apiVersion() + "/models/" + cfg.model() + ":generateContent";
        long start = System.nanoTime();
        int attempt = 0;
        while (true) {
            Failure failure;
            try {
                var entity = client.post().uri(path)
                        .header(KEY_HEADER, cfg.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .exchange((req, res) -> new Raw(res.getStatusCode(), res.getHeaders().getFirst("Retry-After"),
                                new String(res.getBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
                if (entity.status().is2xxSuccessful()) {
                    return parse(request, entity.body(), (System.nanoTime() - start) / 1_000_000);
                }
                failure = classify(entity);
            } catch (ResourceAccessException ex) {
                failure = isTimeout(ex) ? new Failure(ErrorCode.AI_TIMEOUT, true, null)
                        : new Failure(ErrorCode.AI_PROVIDER_UNAVAILABLE, true, null);
            } catch (RestClientException | java.io.UncheckedIOException ex) {
                failure = new Failure(ErrorCode.AI_PROVIDER_UNAVAILABLE, true, null);
            }
            if (!failure.retryable() || attempt >= cfg.maxRetries()) {
                log.warn("Gemini lỗi: model={} result={} attempts={}", cfg.model(), failure.code(), attempt + 1);
                throw new ApiException(failure.code());
            }
            attempt++;
            Duration delay = failure.retryAfter() != null ? failure.retryAfter() : backoff(attempt);
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
            }
        }
    }

    private record Raw(HttpStatusCode status, String retryAfter, String body) { }

    private record Failure(ErrorCode code, boolean retryable, Duration retryAfter) { }

    private Failure classify(Raw r) {
        int s = r.status().value();
        if (s == 429) {
            Duration after = parseRetryAfter(r.retryAfter());
            boolean ok = after != null && after.compareTo(cfg.maxRetryAfter()) <= 0;
            return new Failure(ErrorCode.AI_RATE_LIMITED, ok, ok ? after : null); // không retry mù khi không có Retry-After
        }
        if (s == 401 || s == 403) return new Failure(ErrorCode.AI_NOT_CONFIGURED, false, null);
        if (s >= 500) return new Failure(ErrorCode.AI_PROVIDER_UNAVAILABLE, true, null);
        return new Failure(ErrorCode.AI_PROVIDER_UNAVAILABLE, false, null); // 400/404/...: lỗi cấu hình hoặc request
    }

    static Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) return null;
        try {
            long sec = Long.parseLong(header.trim());
            return sec < 0 ? null : Duration.ofSeconds(sec);
        } catch (NumberFormatException ex) {
            return null; // dạng HTTP-date: bỏ qua, coi như không có
        }
    }

    private Duration backoff(int attempt) {
        long base = cfg.retryBaseDelay().toMillis() << (attempt - 1);
        long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);
        return Duration.ofMillis(base + jitter);
    }

    private static boolean isTimeout(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof HttpTimeoutException || c instanceof SocketTimeoutException) return true;
        }
        return false;
    }

    String buildBody(AiRequest r) {
        ObjectNode root = mapper.createObjectNode();
        root.putObject("systemInstruction").putArray("parts").addObject().put("text", r.systemInstruction());
        ObjectNode content = root.putArray("contents").addObject();
        content.put("role", "user");
        var parts = content.putArray("parts");
        for (String p : r.contentParts()) parts.addObject().put("text", p);
        ObjectNode gen = root.putObject("generationConfig");
        gen.put("temperature", cfg.temperature());
        gen.put("maxOutputTokens", Math.min(r.maxOutputTokens(), cfg.maxOutputTokens()));
        gen.put("responseMimeType", "application/json");
        return mapper.writeValueAsString(root);
    }

    private AiResponse parse(AiRequest request, String raw, long latencyMs) {
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (RuntimeException ex) {
            throw new ApiException(ErrorCode.AI_RESPONSE_INVALID);
        }
        if (root.path("promptFeedback").hasNonNull("blockReason")) {
            throw new ApiException(ErrorCode.AI_RESPONSE_INVALID); // bị chặn an toàn
        }
        JsonNode cand = root.path("candidates").path(0);
        String finish = cand.path("finishReason").asString("");
        if (!finish.isEmpty() && !finish.equals("STOP")) {
            throw new ApiException(ErrorCode.AI_RESPONSE_INVALID); // SAFETY, MAX_TOKENS, RECITATION...
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : cand.path("content").path("parts")) text.append(part.path("text").asString(""));
        if (text.isEmpty()) throw new ApiException(ErrorCode.AI_RESPONSE_INVALID);
        JsonNode u = root.path("usageMetadata");
        AiUsage usage = new AiUsage(u.path("promptTokenCount").asInt(0), u.path("candidatesTokenCount").asInt(0),
                u.path("totalTokenCount").asInt(0));
        return new AiResponse(request.requestId(), text.toString(), cfg.model(), AiSource.PROVIDER, false, usage,
                latencyMs, clock.instant());
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
