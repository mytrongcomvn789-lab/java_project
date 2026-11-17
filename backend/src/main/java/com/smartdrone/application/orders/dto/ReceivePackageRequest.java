package com.smartdrone.application.orders.dto;

import java.util.UUID;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Actor and reception timestamp always come from the server. */
public record ReceivePackageRequest(@NotNull UUID attemptId, @NotNull UUID packageId,
                                    @Size(max = 500) String note) {
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Thuộc tính không được hỗ trợ");
    }
}
