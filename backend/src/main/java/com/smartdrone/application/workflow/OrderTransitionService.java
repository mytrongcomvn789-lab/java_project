package com.smartdrone.application.workflow;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.common.TraceContext;
<<<<<<< Updated upstream
=======
import com.smartdrone.application.realtime.OrderStatusChanged;
import com.smartdrone.application.scheduling.AttemptReleaseService;
>>>>>>> Stashed changes
import com.smartdrone.domain.entity.OrderStatusHistory;
import com.smartdrone.domain.enums.ChangeSource;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.workflow.StatefulOrder;
import com.smartdrone.domain.workflow.TransitionRule;
import com.smartdrone.domain.workflow.TransitionRules;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;

/** Cổng duy nhất để đổi trạng thái đơn: kiểm tra phiên bản, kiểm tra luật, đổi, ghi lịch sử. */
@Service
public class OrderTransitionService {

    private final OrderStatusHistoryRepository historyRepository;
    private final Clock clock;
<<<<<<< Updated upstream

    public OrderTransitionService(OrderStatusHistoryRepository historyRepository, Clock clock) {
        this.historyRepository = historyRepository;
        this.clock = clock;
=======
    private final ApplicationEventPublisher events;
    private final AttemptReleaseService attemptRelease; // null trong test đơn vị cũ

    /** Giữ cho test đơn vị cũ: không phát sự kiện realtime, không trả chỗ. */
    public OrderTransitionService(OrderStatusHistoryRepository historyRepository, Clock clock) {
        this(historyRepository, clock, event -> { });
    }

    public OrderTransitionService(OrderStatusHistoryRepository historyRepository, Clock clock,
                                  ApplicationEventPublisher events) {
        this(historyRepository, clock, events, null);
    }

    @Autowired
    public OrderTransitionService(OrderStatusHistoryRepository historyRepository, Clock clock,
                                  ApplicationEventPublisher events, AttemptReleaseService attemptRelease) {
        this.historyRepository = historyRepository;
        this.clock = clock;
        this.events = events;
        this.attemptRelease = attemptRelease;
>>>>>>> Stashed changes
    }

    /**
     * Người gọi (service của 3.6) phải đang trong một @Transactional và đã nạp `order` trong
     * transaction đó, để việc đổi trạng thái đơn và ghi lịch sử nằm chung một transaction.
     * Kiểm tra quyền sở hữu (vd: khách chỉ hủy đơn của mình) do người gọi làm trước khi gọi hàm này.
     */
    @Transactional
    public TransitionResult transition(StatefulOrder order, TransitionCommand cmd) {
        // 1. Client đang cầm bản cũ? (If-Match)
        if (cmd.expectedVersion() != order.getVersion()) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }

        // 2. Cạnh có tồn tại, đúng vai trò, đủ dữ liệu? (không hợp lệ thì tự ném lỗi)
        OrderStatus from = order.getStatus();
        TransitionRule rule = TransitionRules.validate(
                from, cmd.action(), cmd.actorRole(), cmd.providedFields());

        // 3. Đổi trạng thái. Phiên bản mới = cũ + 1 (Hibernate tự tăng cột @Version khi ghi).
        order.changeStatus(rule.to());
        long newVersion = cmd.expectedVersion() + 1;

        // 4. Ghi lịch sử ngay (flush) để xung đột lộ ra tại đây, không đợi đến lúc commit.
        OrderStatusHistory entry = new OrderStatusHistory(
                order.getId(), newVersion, from, rule.to(), cmd.action(),
                cmd.actorId(), cmd.actorRole(), ChangeSource.API,
                cmd.reason(), cmd.attemptId(), TraceContext.current(), Instant.now(clock));
        try {
            historyRepository.saveAndFlush(entry);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException e) {
            throw new ApiException(ErrorCode.VERSION_CONFLICT);
        }

<<<<<<< Updated upstream
=======
        // 4b. Đơn thất bại hoặc bắt đầu hoàn hàng thì không dùng slot đã đặt nữa: trả chỗ ngay, cùng transaction.
        if (attemptRelease != null
                && (rule.to() == OrderStatus.FAILED || rule.to() == OrderStatus.RETURNING)) {
            attemptRelease.releaseActive(order.getId());
        }

        // 5. Báo realtime: listener chỉ chạy sau commit; rollback thì không có gì được gửi.
        events.publishEvent(new OrderStatusChanged(entry.getId(), order.getId(), newVersion,
                from, rule.to(), rule.event().name(), cmd.attemptId(), entry.getOccurredAt()));

>>>>>>> Stashed changes
        return new TransitionResult(from, rule.to(), rule.event(), newVersion);
    }
        /** Dòng lịch sử đầu tiên khi đơn vừa được tạo: from = null, to = trạng thái ban đầu (PENDING). */
    @Transactional
    public void recordCreation(StatefulOrder order, UUID actorId, RoleName actorRole) {
        OrderStatusHistory entry = new OrderStatusHistory(
                order.getId(), order.getVersion(), null, order.getStatus(), null,
                actorId, actorRole, ChangeSource.API, null, null,
                TraceContext.current(), Instant.now(clock));
        historyRepository.saveAndFlush(entry);
    }
}