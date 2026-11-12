package com.smartdrone.domain.enums;

import java.util.EnumSet;
import java.util.Set;

/** Trạng thái đơn hàng theo hợp đồng APDDMP-9. Tên enum chính là giá trị trả về trong API. */
public enum OrderStatus {
    PENDING("Chờ duyệt"),
    APPROVED("Đã duyệt"),
    REJECTED("Bị từ chối"),
    CANCELLED("Đã hủy"),
    SCHEDULED("Đã lên lịch"),
    PREPARING("Đang chuẩn bị"),
    IN_TRANSIT("Đang giao"),
    AWAITING_PICKUP("Chờ nhận hàng"),
    DELIVERED("Đã giao"),
    FAILED("Giao thất bại"),
    RETURNING("Đang hoàn hàng"),
    RETURNED("Đã hoàn hàng");

    // Trạng thái kết thúc: đơn vào đây là hết, không đi tiếp được nữa.
    private static final Set<OrderStatus> TERMINAL =
            EnumSet.of(REJECTED, CANCELLED, DELIVERED, RETURNED);

    private final String label; // nhãn tiếng Việt để hiển thị trên UI

    OrderStatus(String label) {
        this.label = label;
    }

    public String label() { return label; }

    public boolean isTerminal() { return TERMINAL.contains(this); }
}

