package com.smartdrone.application.ai;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Ngân sách token mỗi ngày cho từng người dùng và toàn hệ thống. Giữ trong bộ nhớ (mất khi khởi động lại,
 * không chia sẻ giữa các instance). Giữ chỗ theo ước lượng rồi điều chỉnh theo số token thật.
 */
@Component
public class AiBudget {
    private final Clock clock;
    private final AiProperties props;
    private final Map<UUID, Long> perUser = new ConcurrentHashMap<>();
    private long global;
    private LocalDate day;

    public AiBudget(Clock clock, AiProperties props) {
        this.clock = clock;
        this.props = props;
    }

    /** Giữ chỗ token; trả null nếu vượt ngân sách người dùng hoặc toàn hệ thống. */
    public synchronized Reservation reserve(UUID userId, long estimatedTokens) {
        rollDay();
        long mine = perUser.getOrDefault(userId, 0L);
        if (mine + estimatedTokens > props.budget().userTokensPerDay()
                || global + estimatedTokens > props.budget().globalTokensPerDay()) {
            return null;
        }
        perUser.put(userId, mine + estimatedTokens);
        global += estimatedTokens;
        return new Reservation(userId, estimatedTokens, day);
    }

    private void rollDay() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(props.budget().zone())));
        if (!today.equals(day)) {
            day = today;
            perUser.clear();
            global = 0;
        }
    }

    private synchronized void adjust(Reservation r, long actual) {
        if (!r.day.equals(day)) return; // đã sang ngày mới
        long delta = actual - r.reserved;
        perUser.merge(r.userId, delta, Long::sum);
        global += delta;
    }

    public final class Reservation {
        private final UUID userId;
        private final long reserved;
        private final LocalDate day;

        Reservation(UUID userId, long reserved, LocalDate day) {
            this.userId = userId;
            this.reserved = reserved;
            this.day = day;
        }

        /** Gọi thành công: tính theo token thật (nếu nhà cung cấp không báo thì giữ nguyên ước lượng). */
        public void settle(int actualTotalTokens) {
            adjust(this, actualTotalTokens > 0 ? actualTotalTokens : reserved);
        }

        /** Gọi thất bại: trả lại phần đã giữ. */
        public void release() {
            adjust(this, 0);
        }
    }
}
