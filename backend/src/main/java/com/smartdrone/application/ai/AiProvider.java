package com.smartdrone.application.ai;

/**
 * Cổng gọi nhà cung cấp AI. Một hàm đồng bộ, tự áp timeout/retry của riêng nó.
 * Lỗi phải được ném dưới dạng ApiException với ErrorCode AI_* (không lộ chi tiết nhà cung cấp ra ngoài).
 */
public interface AiProvider {
    AiResponse generate(AiRequest request);
}
