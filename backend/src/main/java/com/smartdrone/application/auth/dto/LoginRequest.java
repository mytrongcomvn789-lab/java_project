package com.smartdrone.application.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// POST /api/v1/auth/login
public record LoginRequest(
        @NotBlank(message = "Email không được để trống")
        @Size(max = 150, message = "Email tối đa 150 ký tự")
        String email,

        @NotBlank(message = "Mật khẩu không được để trống")
        @Size(max = 200, message = "Mật khẩu quá dài")
        String password) {
}
