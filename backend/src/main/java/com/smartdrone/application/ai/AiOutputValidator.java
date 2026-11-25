package com.smartdrone.application.ai;

import org.springframework.stereotype.Component;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Kiểm schema đầu ra của AI. Kết quả chỉ được dùng làm dữ liệu hiển thị; không bao giờ được thực thi hay dùng để
 * quyết định quyền. Sai schema -> AI_RESPONSE_INVALID.
 */
@Component
public class AiOutputValidator {
    private static final int MAX_TEXT = 4000;
    private static final int MAX_LIST = 20;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonNode validate(AiTask task, String text) {
        JsonNode root;
        try {
            root = mapper.readTree(text == null ? "" : text.strip());
        } catch (RuntimeException ex) {
            throw invalid();
        }
        if (root == null || !root.isObject()) throw invalid();
        switch (task) {
            case ETA -> {
                JsonNode eta = root.path("etaMinutes");
                if (!eta.isIntegralNumber() || eta.asInt() < 0 || eta.asInt() > 10080) throw invalid();
                String c = root.path("confidence").asString("");
                if (!c.equals("LOW") && !c.equals("MEDIUM") && !c.equals("HIGH")) throw invalid();
                requireText(root, "explanation");
            }
            case ORDER_SUMMARY -> {
                requireText(root, "summary");
                requireTextList(root, "highlights");
            }
            case CHAT -> requireText(root, "reply");
            case OPS_ANALYSIS -> {
                requireTextList(root, "insights");
                requireTextList(root, "recommendations");
            }
        }
        return root;
    }

    private static void requireText(JsonNode root, String field) {
        JsonNode n = root.path(field);
        if (!n.isString() || n.asString().isBlank() || n.asString().length() > MAX_TEXT) throw invalid();
    }

    private static void requireTextList(JsonNode root, String field) {
        JsonNode n = root.path(field);
        if (!n.isArray() || n.size() > MAX_LIST) throw invalid();
        for (JsonNode item : n) {
            if (!item.isString() || item.asString().length() > MAX_TEXT) throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.AI_RESPONSE_INVALID);
    }
}
