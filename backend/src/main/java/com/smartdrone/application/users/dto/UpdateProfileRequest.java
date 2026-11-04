package com.smartdrone.application.users.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Size(max = 150, message = "Họ tên tối đa 150 ký tự")
        String fullName,

        @Pattern(regexp = "^(\\+?[0-9]{9,15})?$", message = "Số điện thoại không hợp lệ")
        String phone) { }