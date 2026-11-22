package com.smartdrone.api.realtime;

import org.springframework.context.annotation.Lazy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Broker đơn giản của Spring không tự trả RECEIPT cho SUBSCRIBE (chỉ trả cho DISCONNECT).
 * Interceptor này trả RECEIPT sau khi SUBSCRIBE đã qua StompSecurityInterceptor.
 * SUBSCRIBE bị từ chối thì postSend không được gọi, client nhận khung ERROR như cũ.
 */
@Component
public class StompReceiptInterceptor implements ChannelInterceptor {
    private final MessageChannel clientOutboundChannel;

    public StompReceiptInterceptor(@Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutboundChannel) {
        this.clientOutboundChannel = clientOutboundChannel;
    }

    @Override
    public void postSend(Message<?> message, MessageChannel channel, boolean sent) {
        if (!sent) return;
        StompHeaderAccessor in = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (in == null || in.getCommand() != StompCommand.SUBSCRIBE) return;
        String receipt = in.getReceipt();
        if (receipt == null || in.getSessionId() == null) return;

        StompHeaderAccessor out = StompHeaderAccessor.create(StompCommand.RECEIPT);
        out.setSessionId(in.getSessionId());
        out.setReceiptId(receipt);
        out.setLeaveMutable(true);
        clientOutboundChannel.send(MessageBuilder.createMessage(new byte[0], out.getMessageHeaders()));
    }
}