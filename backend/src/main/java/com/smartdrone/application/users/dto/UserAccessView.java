package com.smartdrone.application.users.dto;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;

// Chỉ trả DTO, không serialize entity hay passwordHash/refresh token.
public record UserAccessView(UUID id, String email, String fullName,
                             RoleName role, boolean enabled, Instant createdAt) {
    public static UserAccessView from(User user) {
        return new UserAccessView(user.getId(), user.getEmail(), user.getFullName(),
                user.getRole().getName(), user.isEnabled(), user.getCreatedAt());
    }
}
