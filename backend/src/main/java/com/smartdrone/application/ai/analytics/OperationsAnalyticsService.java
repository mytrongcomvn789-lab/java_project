package com.smartdrone.application.ai.analytics;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.security.access.prepost.PreAuthorize;
import com.smartdrone.application.ai.*;
import com.smartdrone.application.common.*;
import com.smartdrone.application.statistics.DateBasis;
import com.smartdrone.domain.enums.RoleName;
import tools.jackson.databind.ObjectMapper;

/** Scoped Java/SQL report first; optional AI ranks evidence without authoring measurements or actions. */
@Service
@PreAuthorize("hasRole('LOGISTICS_MANAGER')")
public class OperationsAnalyticsService {
    private static final Set<ErrorCode> RECOVERABLE = Set.of(ErrorCode.AI_NOT_CONFIGURED,
            ErrorCode.AI_TIMEOUT, ErrorCode.AI_PROVIDER_UNAVAILABLE, ErrorCode.AI_RESPONSE_INVALID);
    private final OperationsDataReader reader;
    private final OperationsRules rules;
    private final OperationsNarrative narrative;
    private final AiService ai;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public OperationsAnalyticsService(OperationsDataReader reader, OperationsRules rules,
            OperationsNarrative narrative, AiService ai, Clock clock) {
        this.reader = reader; this.rules = rules; this.narrative = narrative; this.ai = ai; this.clock = clock;
    }

    public AiResult analyze(UUID actor, RoleName role, LocalDate from, LocalDate to, DateBasis basis, UUID station) {
        if (role != RoleName.LOGISTICS_MANAGER) throw new ApiException(ErrorCode.FORBIDDEN);
        var data = reader.read(actor, from, to, basis, station);
        var findings = rules.evaluate(data);
        AiResult generated = null;
        String fallbackReason = null;
        OperationsNarrative.Narrative selected = narrative.fallback(findings.evidence());
        if (data.summary().orders().total() == 0) {
            fallbackReason = "NO_ORDERS_IN_SCOPE";
        } else {
            // Bounded number of short server-authored evidence blocks; no names, addresses or raw rows.
            var blocks = new ArrayList<AiDataBlock>();
            blocks.add(new AiDataBlock("report_scope", "from=" + data.summary().from()
                    + "; to=" + data.summary().to() + "; timezone=" + data.summary().timezone()
                    + "; basis=" + data.summary().dateBasis() + "; stationScope=AUTHORIZED_ONLY; warnings=" + findings.warnings()));
            findings.evidence().forEach(e -> blocks.add(new AiDataBlock("evidence_" + e.id().toLowerCase(java.util.Locale.ROOT),
                    json.writeValueAsString(e))));
            try {
                generated = ai.run(actor, role, AiTask.OPS_ANALYSIS, blocks);
                if (generated.degraded()) fallbackReason = generated.fallbackReason();
                else selected = narrative.select(generated.result(), findings.evidence());
            } catch (ApiException ex) {
                if (ex.getErrorCode() == null || !RECOVERABLE.contains(ex.getErrorCode())) throw ex;
                fallbackReason = ex.getErrorCode().name();
            }
        }
        boolean fallback = fallbackReason != null;
        var result = json.createObjectNode();
        result.set("report", json.valueToTree(data));
        result.set("analysis", json.valueToTree(findings));
        result.put("rulesVersion", OperationsRules.VERSION);
        result.put("narrativeMode", fallback ? "DETERMINISTIC_FALLBACK" : "AI_SELECTED_VERIFIED_EVIDENCE");
        result.put("readOnly", true);
        result.set("insightIds", json.valueToTree(selected.insightIds()));
        result.set("insights", json.valueToTree(selected.insights()));
        result.set("recommendationIds", json.valueToTree(selected.recommendationIds()));
        result.set("recommendations", json.valueToTree(selected.recommendations()));
        return new AiResult(result, generated == null ? null : generated.model(),
                generated != null && generated.mock(), fallback,
                fallback ? AiSource.FALLBACK : generated.source(),
                generated == null ? AiUsage.NONE : generated.usage(),
                generated == null ? UUID.randomUUID() : generated.requestId(),
                clock.instant(), fallbackReason);
    }
}
