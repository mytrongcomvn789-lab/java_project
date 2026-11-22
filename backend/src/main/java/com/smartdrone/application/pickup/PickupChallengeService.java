package com.smartdrone.application.pickup;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.pickup.dto.PickupCodeView;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.entity.PickupChallenge;
import com.smartdrone.domain.enums.AttemptStatus;
import com.smartdrone.domain.enums.ChallengeStatus;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.PickupChallengeRepository;

/**
 * Cấp mã nhận hàng cho customer. Dùng lại challenge còn hạn; hết hạn, bị khóa hoặc đổi lần giao thì cấp mới.
 * Hai request đầu tiên chạy song song: unique (order_id, sequence_no) chặn trùng, request thua đọc lại bản thắng.
 */
@Service
@PreAuthorize("hasRole('CUSTOMER')")
public class PickupChallengeService {
    private final OrderRepository orders;
    private final DeliveryAttemptRepository attempts;
    private final PickupChallengeRepository challenges;
    private final PickupCodeCodec codec;
    private final PickupProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PickupChallengeService(OrderRepository orders, DeliveryAttemptRepository attempts,
            PickupChallengeRepository challenges, PickupCodeCodec codec, PickupProperties props, Clock clock,
            PlatformTransactionManager txManager) {
        this.orders = orders;
        this.attempts = attempts;
        this.challenges = challenges;
        this.codec = codec;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    public PickupCodeView currentCode(UUID customerId, UUID orderId) {
        try {
            return tx.execute(status -> issueOrReuse(customerId, orderId));
        } catch (DataIntegrityViolationException race) {
            return tx.execute(status -> issueOrReuse(customerId, orderId));
        }
    }

    private PickupCodeView issueOrReuse(UUID customerId, UUID orderId) {
        Order order = orders.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (order.getStatus() != OrderStatus.AWAITING_PICKUP) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Mã nhận hàng chỉ có khi đơn đã đến trạm nhận (Chờ nhận hàng)");
        }
        var active = attempts.findByOrderIdOrderByAttemptNoAsc(orderId).stream()
                .filter(a -> a.getStatus() == AttemptStatus.ACTIVE).toList();
        if (active.size() != 1) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Đơn chưa có lần giao đang hiệu lực");
        }
        UUID attemptId = active.getFirst().getId();
        Instant now = clock.instant();

        var latest = challenges.findFirstByOrderIdOrderBySequenceNoDesc(orderId);
        if (latest.isPresent()) {
            PickupChallenge c = latest.get();
            if (c.getStatus() == ChallengeStatus.ACTIVE && c.getAttemptId().equals(attemptId)
                    && c.getExpiresAt().isAfter(now)) {
                return view(c);
            }
            if (c.getStatus() == ChallengeStatus.ACTIVE) challenges.revoke(c.getId());
        }
        int next = latest.map(c -> c.getSequenceNo() + 1).orElse(1);
        var created = challenges.saveAndFlush(new PickupChallenge(orderId, attemptId, order.getCustomerId(),
                order.getDropoffStationId(), next, codec.newNonce(), now.plus(props.challengeTtl()), now));
        return view(created);
    }

    private PickupCodeView view(PickupChallenge c) {
        return new PickupCodeView(c.getId(), c.getOrderId(), codec.otp(c), codec.qrToken(c), c.getExpiresAt());
    }
}