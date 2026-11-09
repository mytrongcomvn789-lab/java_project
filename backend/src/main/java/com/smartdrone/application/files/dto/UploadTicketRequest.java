package com.smartdrone.application.files.dto;

import com.smartdrone.domain.enums.FilePurpose;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UploadTicketRequest(
        @NotNull(message = "purpose là bắt buộc") FilePurpose purpose,
        @NotBlank(message = "contentType là bắt buộc") String contentType,
        @NotNull(message = "sizeBytes là bắt buộc") @Positive(message = "sizeBytes phải lớn hơn 0") Long sizeBytes) { }