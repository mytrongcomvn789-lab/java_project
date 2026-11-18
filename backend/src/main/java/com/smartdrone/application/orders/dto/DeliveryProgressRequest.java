package com.smartdrone.application.orders.dto;

import jakarta.validation.constraints.Size;

/** 4.4: body tùy chọn của DISPATCH/ARRIVE. Actor và thời điểm luôn lấy từ server, không nhận từ client. */
public record DeliveryProgressRequest(@Size(max = 500) String note) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Thuộc tính không được hỗ trợ");
    }
}
