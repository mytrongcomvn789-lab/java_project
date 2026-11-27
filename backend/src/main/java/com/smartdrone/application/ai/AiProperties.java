package com.smartdrone.application.ai;

import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cấu hình AI (smartdrone.ai.*). Khóa nhà cung cấp chỉ đọc từ biến môi trường SMARTDRONE_AI_GEMINI_API_KEY.
 * Các giá trị mặc định về hạn mức là ƯỚC LƯỢNG THẤP HƠN gói miễn phí; phải đối chiếu với trang rate limit trong AI Studio.
 */
@ConfigurationProperties(prefix = "smartdrone.ai")
public record AiProperties(
        @DefaultValue("AUTO") Provider provider,
        @DefaultValue("false") boolean fallbackEnabled,
        @DefaultValue("false") boolean sendPersonalData,
        @DefaultValue("1000") int maxChatMessageChars,
        @DefaultValue Gemini gemini,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Budget budget,
        @DefaultValue Breaker breaker) {

    /** AUTO: có key thì Gemini; không có key thì Mock (chỉ profile dev/test) hoặc Disabled. */
    public enum Provider { AUTO, DISABLED, MOCK, GEMINI }

    private static final Pattern MODEL = Pattern.compile("[A-Za-z0-9._-]{1,80}");

    public AiProperties {
        if (maxChatMessageChars < 1 || maxChatMessageChars > 8000) {
            throw new IllegalStateException("smartdrone.ai.max-chat-message-chars phải trong 1..8000");
        }
    }

    public record Gemini(
            String apiKey,
            // Cần kiểm tra lại: Google đổi tên/ngừng model thường xuyên và hạn mức gói miễn phí khác nhau theo model.
            @DefaultValue("gemini-3.5-flash-lite") String model,
            @DefaultValue("https://generativelanguage.googleapis.com") String baseUrl,
            @DefaultValue("v1beta") String apiVersion,
            @DefaultValue("PT3S") Duration connectTimeout,
            @DefaultValue("PT15S") Duration readTimeout,
            @DefaultValue("2") int maxRetries,
            @DefaultValue("PT0.5S") Duration retryBaseDelay,
            @DefaultValue("PT5S") Duration maxRetryAfter,
            @DefaultValue("1024") int maxOutputTokens,
            @DefaultValue("0.2") double temperature) {
        public Gemini {
            if (model == null || !MODEL.matcher(model).matches()) {
                throw new IllegalStateException("smartdrone.ai.gemini.model không hợp lệ");
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException("smartdrone.ai.gemini.base-url không được trống");
            }
            if (maxRetries < 0 || maxRetries > 2) {
                throw new IllegalStateException("smartdrone.ai.gemini.max-retries phải trong 0..2");
            }
            if (maxOutputTokens < 16) {
                throw new IllegalStateException("smartdrone.ai.gemini.max-output-tokens quá nhỏ");
            }
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** Không bao giờ in khóa. */
        @Override
        public String toString() {
            return "Gemini[model=" + model + ", baseUrl=" + baseUrl + ", apiKeyConfigured=" + hasApiKey() + "]";
        }
    }

    /** Cửa sổ trượt 60 giây. Mặc định thấp hơn mức miễn phí (ước lượng ~10-15 RPM theo dự án). */
    public record RateLimit(@DefaultValue("5") int perUserPerMinute, @DefaultValue("10") int globalPerMinute) { }

    /** Ngân sách token mỗi ngày (ước lượng, thấp hơn mức miễn phí). Ngày tính theo múi giờ cấu hình. */
    public record Budget(@DefaultValue("20000") long userTokensPerDay,
                         @DefaultValue("200000") long globalTokensPerDay,
                         @DefaultValue("Asia/Ho_Chi_Minh") String zone) { }

    /** Ngắt mạch đơn giản: quá failureThreshold lỗi liên tiếp thì từ chối nhanh trong openDuration. */
    public record Breaker(@DefaultValue("5") int failureThreshold, @DefaultValue("PT30S") Duration openDuration) { }
}
