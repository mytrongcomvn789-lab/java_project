
package com.smartdrone.application.stations.dto;

import com.smartdrone.domain.enums.StationStatus;

import jakarta.validation.constraints.NotNull;

public record StationStatusRequest(

    @NotNull(message = "Trạng thái trạm không được để trống")
    StationStatus status

) {
}
 