package com.smartdrone.api.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.auth.dto.UserProfile;
import com.smartdrone.application.users.UserProfileService;
import com.smartdrone.application.users.dto.ChangePasswordRequest;
import com.smartdrone.application.users.dto.UpdateProfileRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/me")
@Tag(name = "Profile", description = "Hồ sơ người dùng đang đăng nhập")
@SecurityRequirement(name = "bearerAuth")
public class ProfileController {
    private final UserProfileService service;

    public ProfileController(UserProfileService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Xem hồ sơ của tôi")
    public ResponseEntity<UserProfile> me(JwtAuthenticationToken auth) {
        return noStore(service.get(userId(auth)));
    }

    @PatchMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Sửa họ tên / số điện thoại (không sửa email, role)")
    public ResponseEntity<UserProfile> update(@Valid @RequestBody UpdateProfileRequest request,
                                              JwtAuthenticationToken auth) {
        return noStore(service.update(userId(auth), request));
    }

    @PostMapping("/password")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Đổi mật khẩu; đăng xuất mọi thiết bị")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                               JwtAuthenticationToken auth) {
        service.changePassword(userId(auth), request);
        return ResponseEntity.noContent().build();
    }

    private static UUID userId(JwtAuthenticationToken auth) {
        return UUID.fromString(auth.getName());
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}