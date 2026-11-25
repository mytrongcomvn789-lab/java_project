package com.smartdrone.application.ai;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

/** Ngắt mạch đơn giản: quá ngưỡng lỗi liên tiếp thì từ chối nhanh trong một khoảng, sau đó cho thử lại một lần. */
@Component
public class AiCircuitBreaker {
    private final Clock clock;
    private final AiProperties props;
    private int failures;
    private Instant openUntil;

    public AiCircuitBreaker(Clock clock, AiProperties props) {
        this.clock = clock;
        this.props = props;
    }

    public synchronized boolean allow() {
        if (openUntil == null) return true;
        if (clock.instant().isBefore(openUntil)) return false;
        openUntil = null;       // half-open: cho một lượt thử; thất bại sẽ mở lại ngay
        failures = props.breaker().failureThreshold() - 1;
        return true;
    }

    public synchronized void onSuccess() {
        failures = 0;
        openUntil = null;
    }

    public synchronized void onFailure() {
        if (++failures >= props.breaker().failureThreshold()) {
            openUntil = clock.instant().plus(props.breaker().openDuration());
        }
    }
}
