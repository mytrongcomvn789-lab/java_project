package com.smartdrone.application.ai.analytics;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.smartdrone.application.common.ApiException;
import tools.jackson.databind.ObjectMapper;

class OperationsNarrativeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final OperationsNarrative guard = new OperationsNarrative();
    private final List<OperationsRules.Evidence> evidence = List.of(
            new OperationsRules.Evidence("ORDER_VOLUME","Có 20 đơn.","summary","Kiểm tra bộ lọc."));
    @Test void resolvesIdsToServerTextAndDropsArbitraryFields() {
        var n = guard.select(json.readTree("""
            {"insights":["ORDER_VOLUME"],"recommendations":["ORDER_VOLUME"],
             "total":999,"action":"delete","summary":"Thất bại do nhân viên"}
            """), evidence);
        assertEquals(List.of("Có 20 đơn."), n.insights());
        assertEquals(List.of("Kiểm tra bộ lọc."), n.recommendations());
    }
    @Test void rejectsFreeTextInventedNumbersAndCausality() {
        assertThrows(ApiException.class, () -> guard.select(json.readTree("""
            {"insights":["Có 999 đơn do thời tiết xấu"],"recommendations":[]}
            """), evidence));
    }
    @Test void rejectsEvidenceThatIsNotTrueForThisReport() {
        assertThrows(ApiException.class, () -> guard.select(json.readTree("""
            {"insights":["HIGH_FAILURE"],"recommendations":[]}
            """), evidence));
    }
    @Test void rejectsEmptyInsightsWrongTypesAndMissingFields() {
        for (String input : List.of("{}", "{\"insights\":[],\"recommendations\":[]}",
                "{\"insights\":[5],\"recommendations\":[]}",
                "{\"insights\":[\"ORDER_VOLUME\"],\"recommendations\":{}}"))
            assertThrows(ApiException.class, () -> guard.select(json.readTree(input), evidence));
    }
    @Test void deduplicatesSelectedIdsAndFallbackUsesAllEvidence() {
        var n = guard.select(json.readTree("""
            {"insights":["ORDER_VOLUME","ORDER_VOLUME"],"recommendations":[]}
            """), evidence);
        assertEquals(1,n.insights().size());
        assertEquals(List.of("ORDER_VOLUME"),guard.fallback(evidence).insightIds());
    }
}
