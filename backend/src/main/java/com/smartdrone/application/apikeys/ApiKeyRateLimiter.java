package com.smartdrone.application.apikeys;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Cửa sổ trượt 60 giây theo từng API key, giữ trong bộ nhớ. Đơn giản và đủ cho một instance; nhiều instance
 * cần chuyển sang bộ đếm tập trung (Redis...), ghi trong docs/03-architecture.
 */
@Component
public class ApiKeyRateLimiter {
    private final Map<UUID, Deque<Instant>> hits = new ConcurrentHashMap<>();
    private final Clock clock;

    public ApiKeyRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** true nếu request này được phép (và đã được tính vào cửa sổ); false nếu vượt giới hạn. */
    public boolean allow(UUID keyId, int limitPerMinute) {
        Instant now = clock.instant();
        Instant windowStart = now.minusSeconds(60);
        Deque<Instant> timestamps = hits.computeIfAbsent(keyId, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(windowStart)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= limitPerMinute) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    /** Chỉ cho test: dọn bộ đếm giữa các kịch bản. */
    public void reset(UUID keyId) {
        hits.remove(keyId);
    }
}