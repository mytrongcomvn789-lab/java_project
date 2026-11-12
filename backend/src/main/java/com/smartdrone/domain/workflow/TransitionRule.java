package com.smartdrone.domain.workflow;

import java.util.Set;

import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;

/** Một cạnh của sơ đồ: từ trạng thái `from`, làm `action`, sang `to`. */
public record TransitionRule(
        OrderStatus from,
        OrderAction action,
        OrderStatus to,
        Set<RoleName> roles,            // ai được phép
        Set<RequiredField> required,    // dữ liệu bắt buộc
        OrderEvent event                // sự kiện phát sinh
) { }