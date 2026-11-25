package com.smartdrone.application.ai;

/** Số token đã dùng, theo báo cáo của nhà cung cấp (0 nếu không có). */
public record AiUsage(int promptTokens, int outputTokens, int totalTokens) {
    public static final AiUsage NONE = new AiUsage(0, 0, 0);
}
