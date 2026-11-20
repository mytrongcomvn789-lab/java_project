package com.smartdrone.api.realtime;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import com.smartdrone.domain.enums.RoleName;

/**
 * Nhớ "phiên STOMP nào thuộc người nào, token hết hạn lúc nào" và đóng phiên khi token hết hạn.
 * Quyền xem đơn được kiểm lúc SUBSCRIBE; token hết hạn thì kết nối bị đóng, client phải làm mới token và nối lại.
 */
@Component
public class StompSessionRegistry {
    private static final Logger log = LoggerFactory.getLogger(StompSessionRegistry.class);

    public record SessionInfo(UUID userId, RoleName role, Instant expiresAt) { }

    private final Map<String, SessionInfo> authenticated = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sockets = new ConcurrentHashMap<>();
    private final Clock clock;

    public StompSessionRegistry(Clock clock) {
        this.clock = clock;
    }

    public void attach(WebSocketSession session) {
        sockets.put(session.getId(), session);
    }

    public void detach(String sessionId) {
        sockets.remove(sessionId);
        authenticated.remove(sessionId);
    }

    public void authenticate(String sessionId, SessionInfo info) {
        authenticated.put(sessionId, info);
    }

    public Optional<SessionInfo> find(String sessionId) {
        return sessionId == null ? Optional.empty() : Optional.ofNullable(authenticated.get(sessionId));
    }

    @Scheduled(fixedDelayString = "${app.realtime.expiry-sweep-interval:PT10S}",
               initialDelayString = "${app.realtime.expiry-sweep-interval:PT10S}")
    public void closeExpired() {
        Instant now = clock.instant();
        authenticated.forEach((sessionId, info) -> {
            if (info.expiresAt().isAfter(now)) return;
            WebSocketSession socket = sockets.get(sessionId);
            if (socket == null) return;
            try {
                log.info("WS_CLOSE_EXPIRED session={} user={}", sessionId, info.userId());
                socket.close(CloseStatus.POLICY_VIOLATION.withReason("TOKEN_EXPIRED"));
            } catch (Exception ex) {
                log.debug("Đóng phiên {} thất bại: {}", sessionId, ex.toString());
            }
        });
    }
}