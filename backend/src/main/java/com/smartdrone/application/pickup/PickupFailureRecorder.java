package com.smartdrone.application.pickup;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.domain.entity.PickupChallenge;
import com.smartdrone.infrastructure.persistence.PickupChallengeRepository;

/**
 * Ghi lần nhập sai trong transaction RIÊNG (REQUIRES_NEW): request sau đó ném lỗi và rollback
 * transaction chính, nhưng bộ đếm sai vẫn phải được giữ, nếu không có thể thử mã vô hạn.
 */
@Component
public class PickupFailureRecorder {
    private final PickupChallengeRepository challenges;

    public PickupFailureRecorder(PickupChallengeRepository challenges) {
        this.challenges = challenges;
    }

    /** @return số lần sai hiện tại sau khi cộng; đạt max thì challenge chuyển LOCKED. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int record(UUID challengeId, int max) {
        challenges.incrementFailures(challengeId);
        challenges.lockIfExceeded(challengeId, max);
        return challenges.findById(challengeId).map(PickupChallenge::getFailedAttempts).orElse(max);
    }
}