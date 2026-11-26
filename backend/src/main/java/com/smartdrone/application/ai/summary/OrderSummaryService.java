package com.smartdrone.application.ai.summary;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.smartdrone.application.ai.AiDataAccessGuard;
import com.smartdrone.application.ai.AiDataBlock;
import com.smartdrone.application.ai.AiResult;
import com.smartdrone.application.ai.AiService;
import com.smartdrone.application.ai.AiSource;
import com.smartdrone.application.ai.AiTask;
import com.smartdrone.application.ai.AiUsage;
import com.smartdrone.application.ai.summary.OrderSummaryVerifier.Summary;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.enums.RoleName;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 7.3: tóm tắt giao hàng từ timeline của MỘT đơn. Luồng: kiểm quyền (404 nếu ngoài phạm vi) -> dựng dữ kiện xác
 * định -> gọi LLM chỉ với khối dữ kiện đó -> kiểm chứng đầu ra -> nếu AI tắt/lỗi/không qua kiểm chứng thì dùng bản
 * xác định. Lỗi rate limit, ngân sách và quyền KHÔNG bị che bởi fallback. Chỉ đọc, không đổi trạng thái đơn.
 */
@Service
@PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR')")
public class OrderSummaryService {
    public static final String VERSION = "ORDER_SUMMARY_V1";
    private static final Set<ErrorCode> RECOVERABLE = Set.of(ErrorCode.AI_NOT_CONFIGURED, ErrorCode.AI_TIMEOUT,
            ErrorCode.AI_PROVIDER_UNAVAILABLE, ErrorCode.AI_RESPONSE_INVALID);

    private final AiDataAccessGuard guard;
    private final AiService ai;
    private final OrderSummaryVerifier verifier;
    private final OrderSummaryFallback fallback;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public OrderSummaryService(AiDataAccessGuard guard, AiService ai, OrderSummaryVerifier verifier,
                               OrderSummaryFallback fallback, Clock clock) {
        this.guard = guard; this.ai = ai; this.verifier = verifier; this.fallback = fallback; this.clock = clock;
    }

    public AiResult summarize(UUID userId, RoleName role, UUID orderId) {
        var order = guard.orderForSummary(userId, role, orderId);
        var facts = OrderSummaryFacts.from(order, clock.instant());
        AiResult generated = null;
        String fallbackReason = null;
        Summary summary = null;
        try {
            generated = ai.run(userId, role, AiTask.ORDER_SUMMARY,
                    List.of(new AiDataBlock("order_timeline", facts.toPromptText())));
            if (generated.degraded()) fallbackReason = generated.fallbackReason();
            else summary = verifier.verify(generated.result(), facts);
        } catch (ApiException ex) {
            if (ex.getErrorCode() == null || !RECOVERABLE.contains(ex.getErrorCode())) throw ex;
            fallbackReason = ex.getErrorCode().name();
        }
        boolean degraded = summary == null;
        if (degraded) summary = fallback.build(facts);

        ObjectNode result = json.createObjectNode();
        result.put("orderId", facts.orderId().toString());
        result.put("status", facts.status().name());
        result.put("statusLabel", facts.status().label());
        result.put("summary", summary.summary());
        result.set("highlights", json.valueToTree(summary.highlights()));
        var timeline = result.putArray("timeline");
        facts.steps().forEach(s -> {
            ObjectNode n = timeline.addObject();
            n.put("status", s.status().name());
            n.put("label", s.status().label());
            n.put("at", s.at().toString());
            if (s.minutesSincePrevious() == null) n.putNull("minutesSincePrevious");
            else n.put("minutesSincePrevious", s.minutesSincePrevious());
        });
        result.put("summaryVersion", VERSION);
        result.put("mode", degraded ? "DETERMINISTIC_FALLBACK" : "AI_VERIFIED");
        result.put("readOnly", true);
        return new AiResult(result, generated == null ? null : generated.model(),
                generated != null && generated.mock(), degraded,
                degraded ? AiSource.FALLBACK : generated.source(),
                generated == null ? AiUsage.NONE : generated.usage(),
                generated == null ? UUID.randomUUID() : generated.requestId(), clock.instant(), fallbackReason);
    }
}
