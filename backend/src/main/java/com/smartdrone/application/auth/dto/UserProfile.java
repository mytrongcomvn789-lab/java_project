package com.smartdrone.application.auth.dto;

import java.time.Instant;
import java.util.UUID;

import com.smartdrone.domain.entity.User;

public record UserProfile(
        UUID id,
        String email,
        String fullName,
        String phone,
        String role,
        Instant createdAt) {

    public static UserProfile from(User user) {
        return new UserProfile(user.getId(), user.getEmail(), user.getFullName(),
                user.getPhone(), user.getRole().getName().name(), user.getCreatedAt());
    }
}