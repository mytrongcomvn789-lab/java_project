package com.smartdrone.application.stations.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.smartdrone.domain.enums.StationStatus;

import jakarta.validation.constraints.NotNull;

public record StationStatusInput(
        @NotNull(message = "Cần chọn trạng thái trạm") StationStatus status) {
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Thuộc tính không được hỗ trợ");
    }
}
