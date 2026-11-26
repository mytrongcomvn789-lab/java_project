package com.smartdrone.application.ai.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import com.smartdrone.application.ai.*;
import com.smartdrone.application.common.*;
import com.smartdrone.domain.enums.RoleName;
import tools.jackson.databind.ObjectMapper;

/** Customer-only orchestration; provider cannot select users, fetch data, or execute business commands. */
@Service
@PreAuthorize("hasRole('CUSTOMER')")
public class CustomerChatService {
    private final AiService ai;
    private final AiDataAccessGuard guard;
    private final AiProperties properties;
    private final ChatHistoryStore history;
    private final ChatKnowledge knowledge;
    private final ObjectMapper json = new ObjectMapper();

    public CustomerChatService(AiService ai, AiDataAccessGuard guard, AiProperties properties,
            ChatHistoryStore history, ChatKnowledge knowledge) {
        this.ai = ai; this.guard = guard; this.properties = properties;
        this.history = history; this.knowledge = knowledge;
    }

    public AiResult chat(UUID userId, RoleName role, UUID conversationId, UUID orderId, String message) {
        if (role != RoleName.CUSTOMER) throw new ApiException(ErrorCode.FORBIDDEN);
        if (message == null || message.isBlank() || message.length() > properties.maxChatMessageChars())
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Tin nhắn không hợp lệ hoặc quá dài.");
        // Check a supplied order before creating any conversation or calling a provider.
        if (orderId != null) guard.orderBlocks(AiTask.CHAT, userId, role, orderId);
        var ticket = history.begin(userId, conversationId, orderId);
        try {
            List<AiDataBlock> blocks = new ArrayList<>();
            blocks.add(knowledge.block());
            if (ticket.orderId() != null) {
                // Recheck on EVERY turn (including omitted orderId); never trust a previous snapshot.
                blocks.addAll(guard.orderBlocks(AiTask.CHAT, userId, role, ticket.orderId()));
            }
            blocks.add(new AiDataBlock("untrusted_conversation_history", ticket.history()));
            String question = clean(message);
            blocks.add(new AiDataBlock("user_message", question));
            AiResult generated = ai.run(userId, role, AiTask.CHAT, blocks);
            String reply = generated.degraded()
                ? "Dịch vụ AI đang tạm thời không sẵn sàng. Vui lòng xem trạng thái trong chi tiết đơn hoặc thử lại sau. "
                  + "Chatbot chưa thực hiện bất kỳ thao tác nào trên đơn."
                : clean(generated.result().path("reply").asString());
            if (reply.isBlank()) throw new ApiException(ErrorCode.AI_RESPONSE_INVALID);
            // Whitelist output fields; ignore tool calls, arbitrary links and conversation IDs from the model.
            var result = json.createObjectNode()
                .put("reply", reply)
                .put("conversationId", ticket.conversationId().toString())
                .put("knowledgeVersion", ChatKnowledge.VERSION)
                .put("readOnly", true);
            if (ticket.orderId() != null) result.put("orderId", ticket.orderId().toString());
            else result.putNull("orderId");
            result.set("sources", json.valueToTree(knowledge.sources()));
            history.complete(ticket, question, reply);
            return new AiResult(result, generated.model(), generated.mock(), generated.degraded(),
                generated.source(), generated.usage(), generated.requestId(), generated.generatedAt(),
                generated.fallbackReason());
        } catch (RuntimeException error) {
            history.abandon(ticket);
            throw error;
        }
    }

    /** Always redact chat content even when another AI task opts in to PII. */
    private static String clean(String text) {
        String safe = PersonalDataRedactor.redact(text, false);
        return safe.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
    }
}
