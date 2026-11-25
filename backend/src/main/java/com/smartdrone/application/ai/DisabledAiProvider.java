package com.smartdrone.application.ai;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;

/** Provider khi không có khóa/không bật AI: luôn báo AI_NOT_CONFIGURED (AiService quyết định có trả fallback hay không). */
public class DisabledAiProvider implements AiProvider {
    @Override
    public AiResponse generate(AiRequest request) {
        throw new ApiException(ErrorCode.AI_NOT_CONFIGURED);
    }
}
