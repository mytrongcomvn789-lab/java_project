package com.smartdrone.application.auth.dto;

import java.time.Instant;
import java.util.UUID;

// Thông tin người dùng trả ra ngoài (không bao giờ có passwordHash).
// Task 2.7 (GET/PATCH /me) dùng lại DTO này.
public record UserProfile(
        UUID id,
        String email,
        String fullName,
        String phone,
        String role,
        Instant createdAt) {
}
