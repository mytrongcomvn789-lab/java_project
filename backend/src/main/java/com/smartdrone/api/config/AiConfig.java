package com.smartdrone.api.config;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.smartdrone.application.ai.AiProperties;
import com.smartdrone.application.ai.AiProvider;
import com.smartdrone.application.ai.DisabledAiProvider;
import com.smartdrone.application.ai.MockAiProvider;
import com.smartdrone.infrastructure.service.GeminiAiProvider;

/**
 * 7.1: chọn provider AI. Mock chỉ được tạo ở profile dev/test/h2/demo; ở profile khác (production) mock bị từ chối
 * và rơi về Disabled. Khóa Gemini chỉ đến từ SMARTDRONE_AI_GEMINI_API_KEY (xem application.yml).
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {
    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public AiProvider aiProvider(AiProperties props, Environment env, Clock clock) {
        boolean devProfile = env.acceptsProfiles(Profiles.of("dev", "test", "h2", "demo"));
        boolean hasKey = props.gemini().hasApiKey();
        return switch (props.provider()) {
            case DISABLED -> disabled("smartdrone.ai.provider=DISABLED");
            case GEMINI -> hasKey ? gemini(props, clock) : disabled("thiếu SMARTDRONE_AI_GEMINI_API_KEY");
            case MOCK -> devProfile ? mock(clock) : disabled("MOCK bị từ chối ngoài profile dev/test");
            case AUTO -> hasKey ? gemini(props, clock)
                    : devProfile ? mock(clock) : disabled("chưa có SMARTDRONE_AI_GEMINI_API_KEY");
        };
    }

    private static AiProvider gemini(AiProperties props, Clock clock) {
        log.info("AI provider: Gemini, model={}", props.gemini().model()); // không in khóa
        return new GeminiAiProvider(props.gemini(), clock);
    }

    private static AiProvider mock(Clock clock) {
        log.warn("AI provider: MOCK (chỉ dev/test; kết quả KHÔNG phải AI thật)");
        return new MockAiProvider(clock);
    }

    private static AiProvider disabled(String reason) {
        log.warn("AI provider: DISABLED ({}). /v1/ai/** trả AI_NOT_CONFIGURED hoặc fallback.", reason);
        return new DisabledAiProvider();
    }
}
