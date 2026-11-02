package com.smartdrone.api.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.auth.AuthService;
import com.smartdrone.application.auth.dto.LoginRequest;
import com.smartdrone.application.auth.dto.RefreshRequest;
import com.smartdrone.application.auth.dto.RegisterRequest;
import com.smartdrone.application.auth.dto.TokenResponse;
import com.smartdrone.application.auth.dto.UserProfile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

// Đường dẫn đầy đủ: /api/v1/auth/... (context-path /api trong application.yml + /v1 ở đây).
// Khớp APDDMP-9: base /api/v1.
@RestController
@RequestMapping("/v1/auth")
@Tag(name = "Auth", description = "Đăng ký, đăng nhập, làm mới token, đăng xuất")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Đăng ký tài khoản khách hàng (role luôn là CUSTOMER)")
    @PostMapping("/register")
    public ResponseEntity<UserProfile> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @Operation(summary = "Đăng nhập, nhận access token và refresh token")
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return noStore(authService.login(request));
    }

    @Operation(summary = "Đổi refresh token lấy cặp token mới (token cũ bị thu hồi)")
    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return noStore(authService.refresh(request));
    }

    @Operation(summary = "Đăng xuất thiết bị hiện tại (thu hồi refresh token)")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }

    // Response chứa token: cấm trình duyệt/proxy lưu cache.
    private static ResponseEntity<TokenResponse> noStore(TokenResponse body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
