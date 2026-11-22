package com.smartdrone.application.orders;

import java.util.Comparator;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.orders.dto.OrderDetail;
import com.smartdrone.application.orders.dto.StatusHistoryView;
import com.smartdrone.application.orders.scope.OrderScopeResolver;
import com.smartdrone.application.orders.scope.OrderSpecs;
import com.smartdrone.application.packages.PackageService;
import com.smartdrone.application.workflow.OrderTransitionService;
import com.smartdrone.application.workflow.TransitionCommand;
import com.smartdrone.domain.entity.DeliveryAttempt;
import com.smartdrone.domain.enums.AttemptStatus;
import com.smartdrone.domain.enums.FailureReason;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;

/**
 * 4.8: điều phối viên báo giao thất bại (FAIL) và bắt đầu hoàn hàng (START_RETURN).
 * Phạm vi dữ liệu giống 4.1; mọi đổi trạng thái đi qua OrderTransitionService (kiểm tra If-Match,
 * luật, ghi lịch sử, trả chỗ của lần thử ACTIVE cùng transaction). Không tự set status ở đây.
 */
@Service
@PreAuthorize("hasRole('DISPATCHER')")
public class OrderFailureService {
    private final OrderRepository orders;
    private final OrderScopeResolver scopes;
    private final OrderTransitionService transitions;
    private final OrderStatusHistoryRepository history;
    private final PackageService packages;
    private final DeliveryAttemptRepository attempts;

    public OrderFailureService(OrderRepository orders, OrderScopeResolver scopes,
            OrderTransitionService transitions, OrderStatusHistoryRepository history,
            PackageService packages, DeliveryAttemptRepository attempts) {
        this.orders = orders; this.scopes = scopes; this.transitions = transitions;
        this.history = history; this.packages = packages; this.attempts = attempts;
    }

    @Transactional
    public OrderDetail fail(UUID actorId, UUID orderId, long version, FailureReason code, String note) {
        if (code == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Vui lòng chọn lý do thất bại");
        }
        String cleaned = clean(note);
        if (code == FailureReason.OTHER && cleaned == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Lý do khác phải kèm ghi chú");
        }
        // Lưu ghi chú nếu có, nếu không thì dùng nhãn của mã để cột reason luôn đọc được.
        String reason = cleaned != null ? cleaned : code.label();
        return run(actorId, orderId, version, OrderAction.FAIL, reason, code);
    }

    @Transactional
    public OrderDetail startReturn(UUID actorId, UUID orderId, long version, String reason) {
        String cleaned = clean(reason);
        if (cleaned == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Vui lòng nhập lý do hoàn hàng");
        }
        return run(actorId, orderId, version, OrderAction.START_RETURN, cleaned, null);
    }

    private OrderDetail run(UUID actorId, UUID orderId, long version, OrderAction action,
                            String reason, FailureReason code) {
        var scope = scopes.resolve(actorId, RoleName.DISPATCHER);
        var order = orders.findOne(OrderSpecs.inScope(scope).and(OrderSpecs.hasId(orderId)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        // Gắn lịch sử với lần thử đang giữ chỗ (FAIL) hoặc lần thử gần nhất (START_RETURN).
        UUID attemptId = attempts.findByOrderIdOrderByAttemptNoAsc(orderId).stream()
                .filter(a -> action == OrderAction.START_RETURN || a.getStatus() == AttemptStatus.ACTIVE)
                .max(Comparator.comparingInt(DeliveryAttempt::getAttemptNo))
                .map(DeliveryAttempt::getId).orElse(null);
        transitions.transition(order, new TransitionCommand(
                action, actorId, RoleName.DISPATCHER, version, reason, attemptId, null, code));
        var entries = history.findByOrderIdOrderByOrderVersionAsc(orderId);
        return OrderDetail.from(order, packages.findByOrderId(orderId).orElse(null),
                entries.stream().map(StatusHistoryView::from).toList());
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
