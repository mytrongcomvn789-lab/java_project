package com.smartdrone.application.scheduling;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.domain.entity.DeliveryAttempt;
import com.smartdrone.domain.enums.AttemptStatus;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;

// 4.2 cung cấp, 4.8 gọi khi đơn FAILED hoặc bắt đầu hoàn hàng: trả chỗ ở trạm gửi và trạm nhận.
// Phải gọi trong transaction đổi trạng thái của 4.8 để trả chỗ và đổi trạng thái cùng thành công hoặc cùng hủy.
@Service
public class AttemptReleaseService {

    private final DeliveryAttemptRepository attempts;
    private final Clock clock;

    public AttemptReleaseService(DeliveryAttemptRepository attempts, Clock clock) {
        this.attempts = attempts;
        this.clock = clock;
    }

    /** Trả chỗ của lần thử đang ACTIVE. Trả true nếu có chỗ được trả, false nếu đơn không giữ chỗ nào. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean releaseActive(UUID orderId) {
        Optional<DeliveryAttempt> active = attempts.findByOrderIdAndStatus(orderId, AttemptStatus.ACTIVE);
        active.ifPresent(a -> a.release(Instant.now(clock)));
        return active.isPresent();
    }
}