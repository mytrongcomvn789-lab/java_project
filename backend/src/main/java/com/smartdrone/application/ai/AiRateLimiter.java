package com.smartdrone.application.ai;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Giới hạn tần suất AI theo người dùng và toàn hệ thống, cửa sổ trượt 60 giây (cùng mẫu ApiKeyRateLimiter).
 * Giữ trong bộ nhớ nên chỉ đúng với MỘT instance; chạy nhiều instance cần bộ đếm tập trung (Redis...).
 */
@Component
public class AiRateLimiter {
    private final Map<UUID, Deque<Instant>> perUser = new ConcurrentHashMap<>();
    private final Deque<Instant> global = new ArrayDeque<>();
    private final Clock clock;
    private final AiProperties props;

    public AiRateLimiter(Clock clock, AiProperties props) {
        this.clock = clock;
        this.props = props;
    }

    /** true nếu được phép (và đã tính vào cửa sổ). Vượt giới hạn người dùng hoặc toàn hệ thống đều trả false. */
    public boolean allow(UUID userId) {
        Instant now = clock.instant();
        Instant start = now.minusSeconds(60);
        Deque<Instant> mine = perUser.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (global) {
            trim(global, start);
            synchronized (mine) {
                trim(mine, start);
                if (mine.size() >= props.rateLimit().perUserPerMinute()
                        || global.size() >= props.rateLimit().globalPerMinute()) {
                    return false;
                }
                mine.addLast(now);
                global.addLast(now);
                return true;
            }
        }
    }

    private static void trim(Deque<Instant> q, Instant start) {
        while (!q.isEmpty() && q.peekFirst().isBefore(start)) q.pollFirst();
    }
}
