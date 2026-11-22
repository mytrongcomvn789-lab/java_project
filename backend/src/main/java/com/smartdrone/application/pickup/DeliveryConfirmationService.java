package com.smartdrone.application.pickup;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.files.FileService;
import com.smartdrone.application.orders.scope.OrderSpecs;
import com.smartdrone.application.packages.PackageService;
import com.smartdrone.application.pickup.dto.ConfirmDeliveryRequest;
import com.smartdrone.application.pickup.dto.DeliveryConfirmationView;
import com.smartdrone.application.stations.StaffStationScope;
import com.smartdrone.application.workflow.OrderTransitionService;
import com.smartdrone.application.workflow.TransitionCommand;
import com.smartdrone.domain.entity.DeliveryAttempt;
import com.smartdrone.domain.entity.DeliveryConfirmation;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.entity.OrderStatusHistory;
import com.smartdrone.domain.entity.PickupChallenge;
import com.smartdrone.domain.enums.AttemptStatus;
import com.smartdrone.domain.enums.ConfirmationMethod;
import com.smartdrone.domain.enums.FilePurpose;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;
import com.smartdrone.infrastructure.persistence.DeliveryConfirmationRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;
import com.smartdrone.infrastructure.persistence.PickupChallengeRepository;

/**
 * 4.7: operator ở TRẠM NHẬN xác nhận khách đã nhận hàng bằng OTP/QR + ảnh.
 * Mọi bước nằm trong MỘT transaction: lỗi ở bất kỳ bước nào (kể cả lưu DB) thì rollback cả việc đánh dấu
 * challenge đã dùng, gắn ảnh và đổi trạng thái. Ngoại lệ duy nhất là bộ đếm nhập sai (REQUIRES_NEW).
 */
@Service
@PreAuthorize("hasRole('STATION_OPERATOR')")
public class DeliveryConfirmationService {

    public record Result(DeliveryConfirmationView view, boolean replayed) { }

    private final OrderRepository orders;
    private final DeliveryAttemptRepository attempts;
    private final OrderStatusHistoryRepository history;
    private final PickupChallengeRepository challenges;
    private final DeliveryConfirmationRepository confirmations;
    private final PickupFailureRecorder failures;
    private final PickupCodeCodec codec;
    private final PickupProperties props;
    private final FileService files;
    private final StaffStationScope stations;
    private final OrderTransitionService transitions;
    private final Clock clock;

    public DeliveryConfirmationService(OrderRepository orders, DeliveryAttemptRepository attempts,
            OrderStatusHistoryRepository history, PickupChallengeRepository challenges,
            DeliveryConfirmationRepository confirmations, PickupFailureRecorder failures, PickupCodeCodec codec,
            PickupProperties props, FileService files, StaffStationScope stations,
            OrderTransitionService transitions, Clock clock) {
        this.orders = orders;
        this.attempts = attempts;
        this.history = history;
        this.challenges = challenges;
        this.confirmations = confirmations;
        this.failures = failures;
        this.codec = codec;
        this.props = props;
        this.files = files;
        this.stations = stations;
        this.transitions = transitions;
        this.clock = clock;
    }

    @Transactional
    public Result confirm(UUID actor, UUID orderId, long version, ConfirmDeliveryRequest req) {
        String note = validate(req);
        // Ngoài trạm nhận được phân công, hoặc đơn không tồn tại: đều 404, không lộ gì.
        Order order = orders.findOne(dropoffScope(actor).and(OrderSpecs.hasId(orderId)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        // Gửi lại sau khi đã thành công: trả lại kết quả cũ, KHÔNG hoàn thành lần hai.
        if (order.getStatus() == OrderStatus.DELIVERED) return replay(order, actor, req);

        if (version != order.getVersion()) throw new ApiException(ErrorCode.VERSION_CONFLICT);
        if (order.getStatus() != OrderStatus.AWAITING_PICKUP) {
            throw new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Chỉ xác nhận giao khi đơn đang ở trạng thái Chờ nhận hàng");
        }
        DeliveryAttempt attempt = currentAttempt(order);
        Instant now = clock.instant();

        // 1. Mã: tồn tại, còn hạn, chưa khóa, đúng đơn/lần giao/trạm, và đúng giá trị.
        PickupChallenge challenge = usableChallenge(order, attempt, now);
        if (!credentialMatches(challenge, req)) throw wrongCode(challenge);

        // 2. Ảnh: đúng chủ, đúng mục đích, đã upload xong (READY), object còn trên MinIO.
        files.attach(actor, req.photoFileId(), FilePurpose.CONFIRMATION_PHOTO,
                PackageService.RESOURCE_TYPE, orderId);

        // 3. Dùng mã một lần, nguyên tử.
        if (challenges.consume(challenge.getId(), actor, now, props.maxFailedAttempts()) != 1) {
            throw new ApiException(ErrorCode.PICKUP_CODE_USED);
        }

        // 4. State machine: CONFIRM -> DELIVERED, ghi lịch sử, phát sự kiện sau commit.
        var result = transitions.transition(order, new TransitionCommand(OrderAction.CONFIRM, actor,
                RoleName.STATION_OPERATOR, version, note, attempt.getId(), req.photoFileId()));
        Instant confirmedAt = history.findByOrderIdOrderByOrderVersionAsc(orderId).stream()
                .filter(h -> h.getOrderVersion() == result.newVersion())
                .map(OrderStatusHistory::getOccurredAt).findFirst().orElse(now);

        // 5. Bằng chứng giao hàng.
        ConfirmationMethod method = req.hasOtp() ? ConfirmationMethod.OTP : ConfirmationMethod.QR;
        DeliveryConfirmation saved;
        try {
            saved = confirmations.saveAndFlush(new DeliveryConfirmation(orderId, attempt.getId(),
                    challenge.getId(), actor, order.getDropoffStationId(), req.photoFileId(), method, note,
                    confirmedAt, result.newVersion()));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.PICKUP_CODE_USED);
        }
        return new Result(DeliveryConfirmationView.from(saved), false);
    }

    // ----- hỗ trợ -----

    private static String validate(ConfirmDeliveryRequest req) {
        if (req == null || req.photoFileId() == null || req.hasOtp() == req.hasQr()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Cần đúng một trong hai: otp hoặc qrToken, cùng ảnh xác nhận (photoFileId)");
        }
        if (req.note() != null && req.note().length() > 500) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Ghi chú tối đa 500 ký tự");
        }
        return req.note() == null || req.note().isBlank() ? null : req.note().strip();
    }

    private boolean credentialMatches(PickupChallenge c, ConfirmDeliveryRequest req) {
        return req.hasOtp() ? codec.matchesOtp(c, req.otp().strip()) : codec.matchesQr(c, req.qrToken().strip());
    }

    private PickupChallenge usableChallenge(Order order, DeliveryAttempt attempt, Instant now) {
        PickupChallenge c = challenges.findFirstByOrderIdOrderBySequenceNoDesc(order.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.PICKUP_CODE_INVALID,
                        "Khách chưa mở mã nhận hàng cho đơn này"));
        switch (c.getStatus()) {
            case USED -> throw new ApiException(ErrorCode.PICKUP_CODE_USED);
            case LOCKED -> throw new ApiException(ErrorCode.PICKUP_CODE_LOCKED);
            case REVOKED -> throw new ApiException(ErrorCode.PICKUP_CODE_INVALID,
                    "Mã đã được thay bằng mã mới. Nhờ khách mở lại mã nhận hàng");
            case ACTIVE -> { }
        }
        if (!c.getAttemptId().equals(attempt.getId())
                || !c.getCustomerId().equals(order.getCustomerId())
                || !c.getDropoffStationId().equals(order.getDropoffStationId())) {
            throw new ApiException(ErrorCode.PICKUP_CODE_INVALID, "Mã không thuộc lần giao hiện tại của đơn");
        }
        if (!c.getExpiresAt().isAfter(now)) throw new ApiException(ErrorCode.PICKUP_CODE_EXPIRED);
        return c;
    }

    private ApiException wrongCode(PickupChallenge c) {
        int failed = failures.record(c.getId(), props.maxFailedAttempts()); // REQUIRES_NEW, được giữ khi rollback
        int left = props.maxFailedAttempts() - failed;
        return left <= 0
                ? new ApiException(ErrorCode.PICKUP_CODE_LOCKED)
                : new ApiException(ErrorCode.PICKUP_CODE_INVALID,
                        "Mã nhận hàng không đúng. Còn " + left + " lần thử");
    }

    private Result replay(Order order, UUID actor, ConfirmDeliveryRequest req) {
        ApiException delivered = new ApiException(ErrorCode.INVALID_STATE_TRANSITION, "Đơn đã được giao");
        var saved = confirmations.findByOrderId(order.getId())
                .filter(c -> c.getConfirmedBy().equals(actor) && c.getPhotoFileId().equals(req.photoFileId()))
                .orElseThrow(() -> delivered);
        var challenge = challenges.findById(saved.getChallengeId()).orElseThrow(() -> delivered);
        if (!credentialMatches(challenge, req)) throw delivered;
        return new Result(DeliveryConfirmationView.from(saved), true);
    }

    private DeliveryAttempt currentAttempt(Order order) {
        var active = attempts.findByOrderIdOrderByAttemptNoAsc(order.getId()).stream()
                .filter(a -> a.getStatus() == AttemptStatus.ACTIVE).toList();
        if (active.size() != 1) throw invalidAttempt();
        DeliveryAttempt attempt = active.getFirst();
        var lastScheduled = history.findByOrderIdOrderByOrderVersionAsc(order.getId()).stream()
                .filter(h -> h.getAction() == OrderAction.SCHEDULE).reduce((a, b) -> b);
        if (!attempt.getPickupStationId().equals(order.getPickupStationId())
                || !attempt.getDropoffStationId().equals(order.getDropoffStationId())
                || lastScheduled.isEmpty() || !attempt.getId().equals(lastScheduled.get().getAttemptId())) {
            throw invalidAttempt();
        }
        return attempt;
    }

    private Specification<Order> dropoffScope(UUID actor) {
        var ids = stations.stationIdsFor(actor, RoleName.STATION_OPERATOR);
        return (root, q, cb) -> ids.isEmpty() ? cb.disjunction() : root.get("dropoffStationId").in(ids);
    }

    private static ApiException invalidAttempt() {
        return new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                "Lần giao không còn hiệu lực hoặc chưa khớp lịch của đơn. Hãy tải lại và kiểm tra với điều phối viên.");
    }
}