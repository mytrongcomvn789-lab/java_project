package com.smartdrone.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import com.smartdrone.api.realtime.StompReceiptInterceptor;
import com.smartdrone.api.realtime.StompSecurityInterceptor;
import com.smartdrone.api.realtime.StompSessionRegistry;

import jakarta.annotation.PreDestroy;

/** Task 4.3: WebSocket/STOMP tại /api/ws (context-path /api). Broker đơn giản trong bộ nhớ, đủ cho 1 instance. */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT = "/ws";
    private static final long HEARTBEAT_MS = 10_000;

    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;

    private final StompSecurityInterceptor securityInterceptor;
    private final StompReceiptInterceptor receiptInterceptor;
    private final StompSessionRegistry sessions;
    private ThreadPoolTaskScheduler heartbeatScheduler;

    public WebSocketConfig(StompSecurityInterceptor securityInterceptor,
                           StompReceiptInterceptor receiptInterceptor,
                           StompSessionRegistry sessions) {
        this.securityInterceptor = securityInterceptor;
        this.receiptInterceptor = receiptInterceptor;
        this.sessions = sessions;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Kiểm tra Origin như CORS của REST (CorsConfig không áp dụng cho WebSocket).
        registry.addEndpoint(ENDPOINT).setAllowedOrigins(allowedOrigins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Scheduler riêng, không đăng ký thành bean để không đổi TaskScheduler dùng cho @Scheduled.
        heartbeatScheduler = new ThreadPoolTaskScheduler();
        heartbeatScheduler.setPoolSize(1);
        heartbeatScheduler.setThreadNamePrefix("ws-heartbeat-");
        heartbeatScheduler.setDaemon(true);
        heartbeatScheduler.initialize();

        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] {HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(heartbeatScheduler);
        registry.setApplicationDestinationPrefixes("/app"); // không có @MessageMapping nào: client không gửi được gì
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // Thứ tự quan trọng: kiểm tra quyền trước, trả RECEIPT sau.
        registration.interceptors(securityInterceptor, receiptInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(64 * 1024)
                .setSendBufferSizeLimit(512 * 1024)
                .setSendTimeLimit(20_000)
                .setTimeToFirstMessage(10_000) // mở socket mà không gửi CONNECT trong 10 giây thì bị đóng
                .addDecoratorFactory(handler -> new WebSocketHandlerDecorator(handler) {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                        sessions.attach(session);
                        super.afterConnectionEstablished(session);
                    }

                    @Override
                    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                        sessions.detach(session.getId());
                        super.afterConnectionClosed(session, status);
                    }
                });
    }

    @PreDestroy
    void shutdownScheduler() {
        if (heartbeatScheduler != null) heartbeatScheduler.shutdown();
    }
}