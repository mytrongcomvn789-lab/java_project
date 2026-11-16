package com.smartdrone.api.realtime;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

import com.smartdrone.api.security.AccountJwtAuthenticationConverter;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.realtime.OrderChannel;
import com.smartdrone.application.realtime.OrderChannelAccess;
import com.smartdrone.domain.enums.RoleName;

/**
 * Cổng bảo vệ mọi khung STOMP từ client (clientInboundChannel):
 *  - CONNECT: bắt buộc "Authorization: Bearer ..." hợp lệ (chữ ký, hạn, issuer, audience, tài khoản còn hiệu lực);
 *  - SUBSCRIBE: chỉ /topic/orders/{id}, và người đó phải có quyền xem đơn đó;
 *  - SEND và mọi lệnh khác: CẤM, để client không thể giả sự kiện trạng thái.
 * Lỗi ném ra thành khung ERROR; trường "message" của khung ERROR là AUTH_REQUIRED, TOKEN_EXPIRED hoặc FORBIDDEN.
 */
@Component
public class StompSecurityInterceptor implements ChannelInterceptor {
    private static final Logger log = LoggerFactory.getLogger(StompSecurityInterceptor.class);
    private static final String BEARER = "Bearer ";

    private final JwtDecoder jwtDecoder;
    private final AccountJwtAuthenticationConverter accountConverter;
    private final OrderChannelAccess channelAccess;
    private final StompSessionRegistry sessions;
    private final Clock clock;

    public StompSecurityInterceptor(JwtDecoder jwtDecoder, AccountJwtAuthenticationConverter accountConverter,
                                    OrderChannelAccess channelAccess, StompSessionRegistry sessions, Clock clock) {
        this.jwtDecoder = jwtDecoder;
        this.accountConverter = accountConverter;
        this.channelAccess = channelAccess;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // khung nhịp tim, không phải lệnh STOMP
        }
        switch (accessor.getCommand()) {
            case CONNECT, STOMP -> authenticate(accessor);
            case SUBSCRIBE -> authorizeSubscribe(accessor);
            case UNSUBSCRIBE, DISCONNECT -> { /* cho qua */ }
            default -> throw reject(ErrorCode.FORBIDDEN); // SEND, ACK, NACK, BEGIN, COMMIT, ABORT...
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String sessionId = accessor.getSessionId();
        String header = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (sessionId == null || header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw reject(ErrorCode.AUTH_REQUIRED);
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(header.substring(BEARER.length()).trim());
        } catch (JwtException ex) {
            String detail = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            throw reject(detail.contains("expired") ? ErrorCode.TOKEN_EXPIRED : ErrorCode.AUTH_REQUIRED);
        }
        AbstractAuthenticationToken authentication;
        try {
            authentication = accountConverter.convert(jwt); // tài khoản bị khóa/đổi quyền thì token cũ bị loại
        } catch (AuthenticationException ex) {
            throw reject(ErrorCode.AUTH_REQUIRED);
        }
        if (authentication == null || jwt.getExpiresAt() == null) {
            throw reject(ErrorCode.AUTH_REQUIRED);
        }
        RoleName role = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> RoleName.valueOf(a.substring(5)))
                .findFirst()
                .orElseThrow(() -> reject(ErrorCode.FORBIDDEN));
        accessor.setUser(authentication);
        sessions.authenticate(sessionId,
                new StompSessionRegistry.SessionInfo(UUID.fromString(authentication.getName()), role, jwt.getExpiresAt()));
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        StompSessionRegistry.SessionInfo who = sessions.find(accessor.getSessionId())
                .orElseThrow(() -> reject(ErrorCode.AUTH_REQUIRED));
        if (!who.expiresAt().isAfter(clock.instant())) {
            throw reject(ErrorCode.TOKEN_EXPIRED);
        }
        UUID orderId = OrderChannel.parse(accessor.getDestination())
                .orElseThrow(() -> reject(ErrorCode.FORBIDDEN)); // kênh khác, ký tự đại diện: cấm
        if (!channelAccess.canSubscribe(who.userId(), who.role(), orderId)) {
            log.info("WS_SUBSCRIBE_DENIED user={} role={} order={}", who.userId(), who.role(), orderId);
            throw reject(ErrorCode.FORBIDDEN); // ngoài phạm vi và không tồn tại cho cùng kết quả
        }
    }

    private static MessagingException reject(ErrorCode code) {
        return new MessagingException(code.name());
    }
}