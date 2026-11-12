package com.smartdrone.application.workflow;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.domain.workflow.RequiredField;

/** Yêu cầu đổi trạng thái. expectedVersion lấy từ header If-Match. */
public record TransitionCommand(
        OrderAction action,
        UUID actorId,
        RoleName actorRole,
        long expectedVersion,
        String reason,            // có thể null
        UUID attemptId,           // có thể null
        UUID confirmationFileId   // có thể null (ảnh xác nhận giao hàng)
) {
    /** Những dữ liệu bắt buộc mà request này thực sự có. */
    public Set<RequiredField> providedFields() {
        Set<RequiredField> provided = EnumSet.noneOf(RequiredField.class);
        if (reason != null && !reason.isBlank()) provided.add(RequiredField.REASON);
        if (attemptId != null) provided.add(RequiredField.ATTEMPT_ID);
        if (confirmationFileId != null) provided.add(RequiredField.CONFIRMATION_PHOTO);
        return provided;
    }
}