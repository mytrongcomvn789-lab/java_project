package com.smartdrone.application.pickup.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Đúng MỘT trong otp / qrToken. Người thao tác và thời điểm luôn lấy từ server. toString() che mã. */
public record ConfirmDeliveryRequest(@Size(max = 16) String otp, @Size(max = 200) String qrToken,
                                     @NotNull UUID photoFileId, @Size(max = 500) String note) {

    @JsonIgnore
    public boolean hasOtp() { return otp != null && !otp.isBlank(); }

    @JsonIgnore
    public boolean hasQr() { return qrToken != null && !qrToken.isBlank(); }

    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Thuộc tính không được hỗ trợ");
    }

    @Override
    public String toString() {
        return "ConfirmDeliveryRequest[photoFileId=" + photoFileId + ", credential=***]";
    }
}