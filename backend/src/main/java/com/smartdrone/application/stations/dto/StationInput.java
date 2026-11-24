package com.smartdrone.application.stations.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record StationInput(
        @NotBlank(message = "Tên trạm không được để trống")
        @Size(max = 200, message = "Tên trạm tối đa 200 ký tự") String name,
        @NotBlank(message = "Địa chỉ trạm không được để trống")
        @Size(max = 300, message = "Địa chỉ tối đa 300 ký tự") String address,
        @NotNull(message = "Cần nhập sức chứa")
        @Positive(message = "Sức chứa phải là số nguyên dương") Integer capacity) {
    public StationInput {
        name = name == null ? null : name.strip();
        address = address == null ? null : address.strip();
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Thuộc tính không được hỗ trợ");
    }
}
