package com.smartdrone.application.orders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 4.8: bắt đầu hoàn hàng, bắt buộc lý do. */
public record ReturnOrderRequest(
        @NotBlank(message = "Vui lòng nhập lý do hoàn hàng")
        @Size(max = 500, message = "Lý do hoàn hàng tối đa 500 ký tự") String reason) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Thuộc tính không được hỗ trợ");
    }
}
