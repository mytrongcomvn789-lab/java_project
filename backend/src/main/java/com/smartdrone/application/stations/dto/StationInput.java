package com.smartdrone.application.stations.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record StationInput(
        @NotBlank(message = "Tên trạm không được để trống")
        @Size(max = 200, message = "Tên trạm tối đa 200 ký tự") String name,
        @NotNull(message = "Cần nhập vĩ độ")
        @DecimalMin(value = "-90", message = "Vĩ độ phải từ -90 đến 90")
        @DecimalMax(value = "90", message = "Vĩ độ phải từ -90 đến 90") Double latitude,
        @NotNull(message = "Cần nhập kinh độ")
        @DecimalMin(value = "-180", message = "Kinh độ phải từ -180 đến 180")
        @DecimalMax(value = "180", message = "Kinh độ phải từ -180 đến 180") Double longitude,
        @NotNull(message = "Cần nhập sức chứa")
        @Positive(message = "Sức chứa phải là số nguyên dương") Integer capacity) {
    public StationInput {
        name = name == null ? null : name.strip();
    }

    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Thuộc tính không được hỗ trợ");
    }
}
