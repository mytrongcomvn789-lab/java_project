package com.smartdrone.api.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.files.FileService;
import com.smartdrone.application.files.dto.DownloadLinkResponse;
import com.smartdrone.application.files.dto.FileView;
import com.smartdrone.application.files.dto.UploadTicketRequest;
import com.smartdrone.application.files.dto.UploadTicketResponse;
import com.smartdrone.domain.enums.RoleName;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/files")
@Tag(name = "Files", description = "Ảnh gói hàng và ảnh xác nhận (MinIO, bucket private)")
@SecurityRequirement(name = "bearerAuth")
public class FileController {
    private final FileService service;

    public FileController(FileService service) {
        this.service = service;
    }

    @PostMapping("/upload-tickets")
    @PreAuthorize("hasAnyRole('CUSTOMER','STATION_OPERATOR')")
    @Operation(summary = "Xin quyền tải lên có hạn (form POST tới MinIO)")
    public ResponseEntity<UploadTicketResponse> createUploadTicket(
            @Valid @RequestBody UploadTicketRequest request, JwtAuthenticationToken auth) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(service.createUploadTicket(userId(auth), role(auth), request));
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('CUSTOMER','STATION_OPERATOR')")
    @Operation(summary = "Xác nhận đã tải lên; server kiểm tra object, dung lượng, loại thật")
    public ResponseEntity<FileView> complete(@PathVariable UUID id, JwtAuthenticationToken auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.completeUpload(userId(auth), id));
    }

    @GetMapping("/{id}/download-link")
    @PreAuthorize("hasAnyRole('CUSTOMER','STATION_OPERATOR','DISPATCHER')")
    @Operation(summary = "Cấp link xem/tải có hạn (kiểm tra quyền mỗi lần)")
    public ResponseEntity<DownloadLinkResponse> downloadLink(@PathVariable UUID id, JwtAuthenticationToken auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.createDownloadLink(userId(auth), role(auth), id));
    }

    private static UUID userId(JwtAuthenticationToken auth) {
        return UUID.fromString(auth.getName());
    }

    private static RoleName role(JwtAuthenticationToken auth) {
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> RoleName.valueOf(a.substring(5)))
                .findFirst().orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
    }
}