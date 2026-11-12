package com.smartdrone.domain.workflow;

import static com.smartdrone.domain.enums.OrderAction.*;
import static com.smartdrone.domain.enums.OrderStatus.*;
import static com.smartdrone.domain.workflow.OrderEvent.*;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;

/** Nguồn sự thật duy nhất về chuyển trạng thái. Cạnh nào không có ở đây thì bị từ chối. */
public final class TransitionRules {

    private static final Set<RoleName> CUSTOMER = EnumSet.of(RoleName.CUSTOMER);
    private static final Set<RoleName> DISPATCHER = EnumSet.of(RoleName.DISPATCHER);
    private static final Set<RoleName> OPERATOR = EnumSet.of(RoleName.STATION_OPERATOR);
    private static final Set<RequiredField> NONE = EnumSet.noneOf(RequiredField.class);
    private static final Set<RequiredField> REASON = EnumSet.of(RequiredField.REASON);

    private static final List<TransitionRule> RULES = buildRules();

    private TransitionRules() { } // chỉ chứa hàm static, không cho tạo đối tượng

    private static List<TransitionRule> buildRules() {
        List<TransitionRule> r = new ArrayList<>();

        r.add(new TransitionRule(PENDING, APPROVE, APPROVED, DISPATCHER, NONE, ORDER_APPROVED));
        r.add(new TransitionRule(PENDING, REJECT, REJECTED, DISPATCHER, REASON, ORDER_REJECTED));
        r.add(new TransitionRule(PENDING, CANCEL, CANCELLED, CUSTOMER, NONE, ORDER_CANCELLED));

        // Lên lịch lần đầu, hoặc retry sau thất bại: cùng đích SCHEDULED, đều tạo lần thử mới.
        Set<RequiredField> attempt = EnumSet.of(RequiredField.ATTEMPT_ID);
        r.add(new TransitionRule(APPROVED, SCHEDULE, SCHEDULED, DISPATCHER, attempt, ORDER_SCHEDULED));
        r.add(new TransitionRule(FAILED, SCHEDULE, SCHEDULED, DISPATCHER, attempt, ORDER_SCHEDULED));

        r.add(new TransitionRule(SCHEDULED, RECEIVE, PREPARING, OPERATOR, NONE, ORDER_PREPARING));
        r.add(new TransitionRule(PREPARING, DISPATCH, IN_TRANSIT, OPERATOR, NONE, ORDER_DISPATCHED));
        r.add(new TransitionRule(IN_TRANSIT, ARRIVE, AWAITING_PICKUP, OPERATOR, NONE, ORDER_ARRIVED));
        r.add(new TransitionRule(AWAITING_PICKUP, CONFIRM, DELIVERED, OPERATOR,
                EnumSet.of(RequiredField.CONFIRMATION_PHOTO), ORDER_DELIVERED));

        // Bốn trạng thái "đang xử lý" đều có thể thất bại.
        for (OrderStatus s : List.of(SCHEDULED, PREPARING, IN_TRANSIT, AWAITING_PICKUP)) {
            r.add(new TransitionRule(s, FAIL, FAILED, DISPATCHER, REASON, ORDER_FAILED));
        }

        r.add(new TransitionRule(FAILED, START_RETURN, RETURNING, DISPATCHER, REASON, ORDER_RETURN_STARTED));
        r.add(new TransitionRule(RETURNING, COMPLETE_RETURN, RETURNED, OPERATOR, NONE, ORDER_RETURNED));

        return List.copyOf(r); // danh sách không sửa được sau khi tạo
    }

    /** Toàn bộ bảng luật (dùng cho test và tài liệu). */
    public static List<TransitionRule> all() {
        return RULES;
    }

    /** Tìm cạnh khớp (trạng thái hiện tại + hành động). Không thấy nghĩa là cạnh không tồn tại. */
    public static Optional<TransitionRule> find(OrderStatus from, OrderAction action) {
        return RULES.stream()
                .filter(rule -> rule.from() == from && rule.action() == action)
                .findFirst();
    }

    /**
     * Kiểm tra một yêu cầu đổi trạng thái. Hợp lệ thì trả về cạnh, không hợp lệ thì ném ApiException.
     * Thứ tự: cạnh có tồn tại -> đúng vai trò -> đủ dữ liệu bắt buộc.
     */
    public static TransitionRule validate(OrderStatus from, OrderAction action,
                                          RoleName role, Set<RequiredField> provided) {
        TransitionRule rule = find(from, action).orElseThrow(() ->
                new ApiException(ErrorCode.INVALID_STATE_TRANSITION,
                        "Không thể " + action + " khi đơn đang ở trạng thái " + from));

        if (!rule.roles().contains(role)) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        for (RequiredField f : rule.required()) {
            if (!provided.contains(f)) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Thiếu dữ liệu bắt buộc: " + f);
            }
        }
        return rule;
    }

    /** Các hành động mà vai trò này làm được từ trạng thái này (để UI biết hiện nút nào). */
    public static List<OrderAction> allowedActions(OrderStatus from, RoleName role) {
        return RULES.stream()
                .filter(rule -> rule.from() == from && rule.roles().contains(role))
                .map(TransitionRule::action)
                .toList();
    }
}