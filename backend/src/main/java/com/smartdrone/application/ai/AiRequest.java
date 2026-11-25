package com.smartdrone.application.ai;

import java.util.List;
import java.util.UUID;

/**
 * Yêu cầu gửi tới nhà cung cấp. Bất biến.
 * systemInstruction là chỉ dẫn cố định do PromptBuilder dựng; contentParts là dữ liệu (đã gắn nhãn, đã thoát
 * dấu phân cách) và KHÔNG BAO GIỜ được nối vào chỉ dẫn.
 */
public record AiRequest(UUID requestId, AiTask task, UUID userId, String systemInstruction,
                        List<String> contentParts, int maxOutputTokens) {
    public AiRequest {
        if (requestId == null || task == null || userId == null || systemInstruction == null || systemInstruction.isBlank()) {
            throw new IllegalArgumentException("AiRequest thiếu requestId/task/userId/systemInstruction");
        }
        contentParts = List.copyOf(contentParts);
    }

    /** Ước lượng thô số token đầu vào (4 ký tự ≈ 1 token), dùng để kiểm tra ngân sách trước khi gọi. */
    public int estimatedInputTokens() {
        int chars = systemInstruction.length() + contentParts.stream().mapToInt(String::length).sum();
        return chars / 4 + 1;
    }
}
