package com.smartdrone.api.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.smartdrone.application.apikeys.ApiKeyService;
import com.smartdrone.application.apikeys.dto.ApiKeyIssued;
import com.smartdrone.application.apikeys.dto.ApiKeyPage;
import com.smartdrone.application.apikeys.dto.CreateApiKeyRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Quản trị API key cho hệ thống ngoài. Chỉ SYSTEM_ADMIN (RbacPolicy + @PreAuthorize ở ApiKeyService). */
@RestController
@RequestMapping("/v1/admin/api-keys")
@Tag(name = "ApiKeys", description = "5.4: cấp/thu hồi/xoay vòng API key cho hệ thống ngoài")
@SecurityRequirement(name = "bearerAuth")
public class ApiKeyAdminController {
    private final ApiKeyService service;

    public ApiKeyAdminController(ApiKeyService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(summary = "Cấp API key mới. secret chỉ xuất hiện trong response này, không đọc lại được sau.")
    public ResponseEntity<ApiKeyIssued> issue(@Valid @RequestBody CreateApiKeyRequest request,
            JwtAuthenticationToken auth) {
        ApiKeyIssued issued = service.issue(UUID.fromString(auth.getName()), request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(issued.key().id()).toUri();
        return ResponseEntity.created(location).cacheControl(CacheControl.noStore()).body(issued);
    }

    @GetMapping
    @Operation(summary = "Danh sách API key (không có secret), page từ 1, pageSize tối đa 100")
    public ResponseEntity<ApiKeyPage> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(page, pageSize));
    }

    @PostMapping("/{id}/revoke")
    @Operation(summary = "Thu hồi ngay lập tức; mọi request sau đó bằng key này bị từ chối")
    public ResponseEntity<Void> revoke(@PathVariable UUID id, JwtAuthenticationToken auth) {
        service.revoke(UUID.fromString(auth.getName()), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/rotate")
    @Operation(summary = "Đổi secret, giữ nguyên scope; secret cũ ngưng hoạt động ngay")
    public ResponseEntity<ApiKeyIssued> rotate(@PathVariable UUID id, JwtAuthenticationToken auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.rotate(UUID.fromString(auth.getName()), id));
    }
}