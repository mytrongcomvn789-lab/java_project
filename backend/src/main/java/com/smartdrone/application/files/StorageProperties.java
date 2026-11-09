package com.smartdrone.application.files;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Cấu hình kho tệp (app.storage.*). Giới hạn dung lượng/thời hạn link do nhóm chốt, đổi bằng cấu hình.
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
        @DefaultValue("http://localhost:9000") String endpoint,
        String publicEndpoint,
        String accessKey,
        String secretKey,
        @DefaultValue("smartdrone-files") String bucket,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("true") boolean autoCreateBucket,
        @DefaultValue("5242880") long maxFileSizeBytes,
        @DefaultValue("PT5M") Duration uploadTicketTtl,
        @DefaultValue("PT5M") Duration downloadLinkTtl,
        @DefaultValue({"image/jpeg", "image/png", "image/webp"}) List<String> allowedContentTypes,
        @DefaultValue("20") int maxPendingPerUser,
        @DefaultValue("PT1H") Duration pendingGrace,
        @DefaultValue("PT24H") Duration unattachedRetention) {

    public boolean isConfigured() {
        return accessKey != null && !accessKey.isBlank() && secretKey != null && !secretKey.isBlank();
    }

    public String effectivePublicEndpoint() {
        return publicEndpoint == null || publicEndpoint.isBlank() ? endpoint : publicEndpoint;
    }

    // Không in secret ra log/exception.
    @Override
    public String toString() {
        return "StorageProperties[endpoint=" + endpoint + ", bucket=" + bucket
                + ", maxFileSizeBytes=" + maxFileSizeBytes + ", configured=" + isConfigured() + "]";
    }
}