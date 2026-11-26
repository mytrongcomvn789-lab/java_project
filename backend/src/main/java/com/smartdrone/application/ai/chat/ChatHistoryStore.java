package com.smartdrone.application.ai.chat;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import com.smartdrone.application.common.*;
import com.smartdrone.domain.entity.ChatConversation;
import com.smartdrone.infrastructure.persistence.ChatConversationRepository;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

/** Short transactions only: never hold a database lock while calling the AI provider. */
@Service
public class ChatHistoryStore {
    private final ChatConversationRepository repository;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    public ChatHistoryStore(ChatConversationRepository repository, Clock clock) {
        this.repository = repository; this.clock = clock;
    }
    public record Ticket(UUID conversationId, UUID ownerId, UUID orderId, UUID lease, String history) { }

    @Transactional
    public Ticket begin(UUID owner, UUID id, UUID requestedOrder) {
        Instant now = clock.instant();
        ChatConversation c = id == null ? new ChatConversation(owner, requestedOrder, now)
            : repository.lockOwned(id, owner).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!c.getExpiresAt().isAfter(now)) throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        // An omitted orderId continues the existing order. Switching requires a new conversation.
        if (requestedOrder != null && !Objects.equals(c.getOrderId(), requestedOrder))
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Đổi đơn hàng cần tạo hội thoại mới.");
        if (c.getBusyUntil() != null && c.getBusyUntil().isAfter(now))
            throw new ApiException(ErrorCode.DATA_CONFLICT, "Hội thoại đang xử lý. Vui lòng chờ phản hồi.");
        if (c.getTurns() >= 30)
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Hội thoại đã đủ 30 lượt. Hãy tạo hội thoại mới.");
        UUID lease = UUID.randomUUID();
        c.reserve(lease, now.plusSeconds(180));
        repository.saveAndFlush(c);
        return new Ticket(c.getId(), owner, c.getOrderId(), lease, c.getHistory());
    }

    @Transactional
    public void complete(Ticket ticket, String question, String reply) {
        ChatConversation c = ownedLease(ticket);
        if (!c.getExpiresAt().isAfter(clock.instant()))
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        ArrayNode history = (ArrayNode) json.readTree(c.getHistory());
        var turn = json.createObjectNode().put("question", question).put("reply", reply);
        history.add(turn);
        while (history.size() > 3) history.remove(0);
        c.finish(json.writeValueAsString(history));
    }

    @Transactional
    public void abandon(Ticket ticket) {
        repository.lockOwned(ticket.conversationId(), ticket.ownerId()).ifPresent(c -> {
            if (ticket.lease().equals(c.getLease())) c.release();
        });
    }

    private ChatConversation ownedLease(Ticket ticket) {
        ChatConversation c = repository.lockOwned(ticket.conversationId(), ticket.ownerId())
            .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!ticket.lease().equals(c.getLease()))
            throw new ApiException(ErrorCode.DATA_CONFLICT, "Yêu cầu đã hết phiên xử lý. Hãy thử lại.");
        return c;
    }

    @Scheduled(fixedDelay = 3600000)
    @Transactional
    public void purgeExpired() { repository.deleteExpired(clock.instant()); }
}
