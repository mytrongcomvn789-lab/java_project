package com.smartdrone.application.ai.analytics;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import tools.jackson.databind.JsonNode;

/** Only accept IDs of evidence present in THIS scoped dataset; no provider prose reaches the API. */
@Component
public class OperationsNarrative {
    public record Narrative(List<String> insightIds, List<String> insights,
            List<String> recommendationIds, List<String> recommendations) { }

    public Narrative select(JsonNode model, List<OperationsRules.Evidence> evidence) {
        var allowed = evidence.stream().collect(Collectors.toMap(OperationsRules.Evidence::id, Function.identity()));
        var insights = ids(model.path("insights"), allowed);
        var recommendations = ids(model.path("recommendations"), allowed);
        if (insights.isEmpty()) throw new ApiException(ErrorCode.AI_RESPONSE_INVALID);
        return new Narrative(insights, insights.stream().map(id -> allowed.get(id).text()).toList(),
                recommendations, recommendations.stream().map(id -> allowed.get(id).recommendation()).toList());
    }

    public Narrative fallback(List<OperationsRules.Evidence> evidence) {
        return new Narrative(evidence.stream().map(OperationsRules.Evidence::id).toList(),
                evidence.stream().map(OperationsRules.Evidence::text).toList(),
                evidence.stream().map(OperationsRules.Evidence::id).toList(),
                evidence.stream().map(OperationsRules.Evidence::recommendation).distinct().toList());
    }

    private static List<String> ids(JsonNode array, Map<String, OperationsRules.Evidence> allowed) {
        if (!array.isArray() || array.size() > 20) throw new ApiException(ErrorCode.AI_RESPONSE_INVALID);
        var result = new LinkedHashSet<String>();
        for (var item : array) {
            if (!item.isString() || !allowed.containsKey(item.asString()))
                throw new ApiException(ErrorCode.AI_RESPONSE_INVALID);
            result.add(item.asString());
        }
        return List.copyOf(result);
    }
}
