package com.smartdrone.application.orders.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.smartdrone.application.packages.dto.PackageInput;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Tên trường là tạm, phải khớp OpenAPI APDDMP-9 (chốt với Trọng, Nhã, Hòa, Luật). */
public record OrderRequest(
        @NotNull(message = "Cần chọn trạm gửi") UUID pickupStationId,
        @NotNull(message = "Cần chọn trạm nhận") UUID dropoffStationId,
        @NotNull(message = "Cần chọn địa chỉ người nhận") UUID recipientAddressId,
        @JsonProperty("package")
        @NotNull(message = "Cần nhập thông tin gói hàng") @Valid PackageInput packageInfo) {

    // Giống PackageInput: không cho thuộc tính thừa, để khách không thể gửi kèm status, customerId...
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Thuộc tính không được hỗ trợ");
    }
}