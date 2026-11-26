package com.smartdrone.application.ai;

import java.time.Instant;
import java.util.UUID;

/** Phản hồi của nhà cung cấp (text là JSON thô, chưa kiểm schema). degraded=true nghĩa là đây không phải kết quả AI thật. */
public record AiResponse(UUID requestId, String text, String model, AiSource source, boolean degraded,
                         AiUsage usage, long latencyMs, Instant generatedAt) {
    public boolean mock() {
        return source == AiSource.MOCK;
    }
}
