package com.smartdrone.application.addresses.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.*;

public record AddressInput(
        @NotBlank(message = "Nhãn địa chỉ không được để trống")
        @Size(max = 500, message = "Nhãn địa chỉ tối đa 500 ký tự") String label,
        @NotBlank(message = "Tên người nhận không được để trống")
        @Size(max = 500, message = "Tên người nhận tối đa 500 ký tự") String recipientName,
        @NotBlank(message = "Số điện thoại không được để trống")
        @Pattern(regexp = "^\\+?[0-9]{9,15}$", message = "Số điện thoại gồm 9–15 chữ số, có thể bắt đầu bằng +")
        String phone,
        @NotBlank(message = "Địa chỉ không được để trống")
        @Size(max = 500, message = "Địa chỉ tối đa 500 ký tự") String addressLine,
        @NotNull(message = "Cần nhập vĩ độ") @DecimalMin("-90") @DecimalMax("90") Double latitude,
        @NotNull(message = "Cần nhập kinh độ") @DecimalMin("-180") @DecimalMax("180") Double longitude) {
    public AddressInput {
        label = trim(label); recipientName = trim(recipientName);
        phone = trim(phone); addressLine = trim(addressLine);
    }
    private static String trim(String value) { return value == null ? null : value.strip(); }

    // AddressInput in APDDMP-9 forbids additional properties, including forged owner IDs.
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Thuộc tính không được hỗ trợ");
    }
}
