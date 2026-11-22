package com.smartdrone.application.orders.dto;

import com.smartdrone.domain.enums.FailureReason;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 4.8: báo giao thất bại. Bắt buộc mã lý do; OTHER bắt buộc kèm ghi chú (kiểm tra ở service). */
public record FailOrderRequest(
        @NotNull(message = "Vui lòng chọn lý do thất bại") FailureReason reasonCode,
        @Size(max = 500, message = "Ghi chú tối đa 500 ký tự") String note) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Thuộc tính không được hỗ trợ");
    }
}
