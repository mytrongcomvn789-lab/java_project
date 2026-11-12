package com.smartdrone.application.workflow;

import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.workflow.OrderEvent;

/** Kết quả trả cho 3.6: từ đâu, sang đâu, sự kiện nào, phiên bản mới là bao nhiêu. */
public record TransitionResult(OrderStatus from, OrderStatus to, OrderEvent event, long newVersion) { }