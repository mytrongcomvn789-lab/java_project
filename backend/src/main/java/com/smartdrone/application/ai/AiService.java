package com.smartdrone.application.ai;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.smartdrone.application.admin.AuditService;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.enums.RoleName;

import tools.jackson.databind.JsonNode;

/**
 * Facade cho 7.2-7.6: nhận khối dữ liệu ĐÃ được AiDataAccessGuard kiểm quyền, rồi lo phần còn lại:
 * rate limit, ngân sách, ngắt mạch, dựng prompt, gọi provider, kiểm schema, fallback, audit và log metadata.
 * Log/audit chỉ ghi metadata (task, userId, kết quả, token, độ trễ), không ghi nội dung prompt hay dữ liệu cá nhân.
 */
@Service
@PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER')")
public class AiService {
    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    /** Lỗi của nhà cung cấp mà fallback được phép che; rate limit/ngân sách/quyền thì không. */
    private static final Set<ErrorCode> FALLBACK_ELIGIBLE = Set.of(ErrorCode.AI_NOT_CONFIGURED,
            ErrorCode.AI_PROVIDER_UNAVAILABLE, ErrorCode.AI_TIMEOUT, ErrorCode.AI_RESPONSE_INVALID);

    private final AiProvider provider;
    private final PromptBuilder prompts;
    private final AiOutputValidator validator;
    private final AiRateLimiter limiter;
    private final AiBudget budget;
    private final AiCircuitBreaker breaker;
    private final AiProperties props;
    private final AuditService audit;
    private final Clock clock;

    public AiService(AiProvider provider, PromptBuilder prompts, AiOutputValidator validator, AiRateLimiter limiter,
                     AiBudget budget, AiCircuitBreaker breaker, AiProperties props, AuditService audit, Clock clock) {
        this.provider = provider; this.prompts = prompts; this.validator = validator; this.limiter = limiter;
        this.budget = budget; this.breaker = breaker; this.props = props; this.audit = audit; this.clock = clock;
    }

    public AiResult run(UUID userId, RoleName role, AiTask task, List<AiDataBlock> blocks) {
        if (!task.isAllowedFor(role)) throw new ApiException(ErrorCode.FORBIDDEN);
        UUID requestId = UUID.randomUUID();
        long start = System.nanoTime();
        if (!limiter.allow(userId)) {
            throw fail(task, userId, requestId, new ApiException(ErrorCode.AI_RATE_LIMITED), start, null);
        }
        AiRequest request = prompts.build(requestId, task, userId, blocks, props.sendPersonalData(),
                props.gemini().maxOutputTokens());
        AiBudget.Reservation reservation = budget.reserve(userId,
                (long) request.estimatedInputTokens() + request.maxOutputTokens());
        if (reservation == null) {
            throw fail(task, userId, requestId, new ApiException(ErrorCode.AI_BUDGET_EXCEEDED), start, null);
        }
        AiResponse response = null;
        try {
            if (!breaker.allow()) throw new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
            response = provider.generate(request);
            JsonNode node = validator.validate(task, response.text());
            reservation.settle(response.usage().totalTokens());
            breaker.onSuccess();
            AiResult result = new AiResult(node, response.model(), response.mock(), response.degraded(),
                    response.source(), response.usage(), requestId, clock.instant(), null);
            record(task, userId, requestId, "OK", response, elapsedMs(start));
            return result;
        } catch (ApiException ex) {
            return handleFailure(task, userId, requestId, ex, start, response, reservation, true);
        } catch (RuntimeException ex) {
            log.warn("AI task={} requestId={} lỗi không mong đợi: {}", task, requestId, ex.getClass().getSimpleName());
            return handleFailure(task, userId, requestId, new ApiException(ErrorCode.AI_PROVIDER_UNAVAILABLE), start,
                    response, reservation, true);
        }
    }

    private AiResult handleFailure(AiTask task, UUID userId, UUID requestId, ApiException ex, long start,
                                   AiResponse response, AiBudget.Reservation reservation, boolean countBreaker) {
        if (response != null) reservation.settle(response.usage().totalTokens()); else reservation.release();
        ErrorCode code = ex.getErrorCode();
        if (countBreaker && (code == ErrorCode.AI_PROVIDER_UNAVAILABLE || code == ErrorCode.AI_TIMEOUT)) {
            breaker.onFailure();
        }
        ApiException failure = fail(task, userId, requestId, ex, start, response);
        if (props.fallbackEnabled() && code != null && FALLBACK_ELIGIBLE.contains(code)) {
            return new AiResult(null, null, false, true, AiSource.FALLBACK, AiUsage.NONE, requestId,
                    clock.instant(), code.name());
        }
        throw failure;
    }

    private ApiException fail(AiTask task, UUID userId, UUID requestId, ApiException ex, long start, AiResponse resp) {
        record(task, userId, requestId, ex.getErrorCode() == null ? "ERROR" : ex.getErrorCode().name(), resp,
                elapsedMs(start));
        return ex;
    }

    private void record(AiTask task, UUID userId, UUID requestId, String outcome, AiResponse resp, long latencyMs) {
        int tokens = resp == null ? 0 : resp.usage().totalTokens();
        String model = resp == null ? "-" : resp.model();
        boolean mock = resp != null && resp.mock();
        log.info("AI task={} userId={} requestId={} outcome={} model={} mock={} tokens={} latencyMs={}",
                task, userId, requestId, outcome, model, mock, tokens, latencyMs);
        try { // nội dung audit chỉ là metadata; lỗi ghi audit không được làm hỏng request
            audit.recordIndependently(userId, "AI_REQUEST", "AI_TASK", task.name(),
                    "requestId=" + requestId + " outcome=" + outcome + " model=" + model + " mock=" + mock
                            + " tokens=" + tokens + " latencyMs=" + latencyMs);
        } catch (RuntimeException ex) {
            log.warn("Không ghi được audit AI: {}", ex.getClass().getSimpleName());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
