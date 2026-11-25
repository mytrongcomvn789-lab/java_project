package com.smartdrone.application.ai;

import java.time.Clock;

/**
 * Provider giả CHỈ cho profile dev/test (AiConfig không tạo nó ở profile khác). Mọi phản hồi gắn source=MOCK, mock=true;
 * không phải kết quả AI thật, không được dùng để kết luận chất lượng AI.
 */
public class MockAiProvider implements AiProvider {
    public static final String MODEL = "mock";
    private final Clock clock;

    public MockAiProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public AiResponse generate(AiRequest request) {
        String text = switch (request.task()) {
            case ETA -> "{\"etaMinutes\":25,\"confidence\":\"LOW\",\"explanation\":\"[MOCK] Dữ liệu giả lập, không phải ước lượng thật.\"}";
            case ORDER_SUMMARY -> "{\"summary\":\"[MOCK] Tóm tắt giả lập, không phải kết quả AI thật.\",\"highlights\":[\"[MOCK]\"]}";
            case CHAT -> "{\"reply\":\"[MOCK] Câu trả lời giả lập, không phải kết quả AI thật.\"}";
            case OPS_ANALYSIS -> "{\"insights\":[\"[MOCK] Nhận định giả lập.\"],\"recommendations\":[\"[MOCK] Khuyến nghị giả lập.\"]}";
        };
        return new AiResponse(request.requestId(), text, MODEL, AiSource.MOCK, false,
                new AiUsage(0, 0, 0), 0, clock.instant());
    }
}
