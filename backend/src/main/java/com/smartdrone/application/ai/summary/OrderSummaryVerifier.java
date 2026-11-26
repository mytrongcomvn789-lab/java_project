package com.smartdrone.application.ai.summary;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;

import tools.jackson.databind.JsonNode;

/**
 * Kiểm đầu ra AI của 7.3 so với dữ kiện đã cấp. Không thể "chứng minh" một đoạn văn tự do đúng, nên áp các ràng buộc
 * chặn kiểu sai nguy hiểm nhất: độ dài, không URL/email/dấu phân cách, và MỌI con số xuất hiện (giờ, ngày, số bước,
 * số phút) phải có trong dữ kiện. Vi phạm -> AI_RESPONSE_INVALID để service quay về bản tóm tắt xác định.
 */
@Component
public class OrderSummaryVerifier {
    static final int MAX_SUMMARY = 800, MAX_HIGHLIGHTS = 5, MAX_HIGHLIGHT = 200;
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern FORBIDDEN = Pattern.compile("(?i)https?:|www\\.|@|<<|>>");

    public record Summary(String summary, List<String> highlights) { }

    public Summary verify(JsonNode model, OrderSummaryFacts facts) {
        Set<String> allowed = numbers(facts.toPromptText());
        JsonNode s = model.path("summary");
        if (!s.isString() || s.asString().isBlank() || s.asString().length() > MAX_SUMMARY) throw invalid();
        String summary = clean(s.asString());
        check(summary, allowed);
        JsonNode h = model.path("highlights");
        if (!h.isArray() || h.size() > MAX_HIGHLIGHTS) throw invalid();
        List<String> highlights = new ArrayList<>();
        for (JsonNode item : h) {
            if (!item.isString() || item.asString().isBlank() || item.asString().length() > MAX_HIGHLIGHT) throw invalid();
            String text = clean(item.asString());
            check(text, allowed);
            highlights.add(text);
        }
        return new Summary(summary, List.copyOf(highlights));
    }

    private static void check(String text, Set<String> allowed) {
        if (FORBIDDEN.matcher(text).find()) throw invalid();
        for (String n : numbers(text)) {
            if (!allowed.contains(n)) throw invalid();
        }
    }

    private static Set<String> numbers(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) out.add(m.group().replaceFirst("^0+(?=.)", "")); // "03" và "3" là cùng một số
        return out;
    }

    private static String clean(String text) {
        return text.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.AI_RESPONSE_INVALID);
    }
}
