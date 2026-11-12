package com.smartdrone.domain.workflow;

/** Sự kiện phát sinh khi chuyển trạng thái thành công (để 3.6 và tracking lắng nghe sau này). */
public enum OrderEvent {
    ORDER_APPROVED, ORDER_REJECTED, ORDER_CANCELLED, ORDER_SCHEDULED, ORDER_PREPARING,
    ORDER_DISPATCHED, ORDER_ARRIVED, ORDER_DELIVERED, ORDER_FAILED,
    ORDER_RETURN_STARTED, ORDER_RETURNED
}