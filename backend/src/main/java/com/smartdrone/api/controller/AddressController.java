package com.smartdrone.api.controller;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import com.smartdrone.application.addresses.AddressService;
import com.smartdrone.application.addresses.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/addresses")
@PreAuthorize("hasRole('CUSTOMER')")
@Tag(name = "Addresses", description = "Sổ địa chỉ riêng của khách hàng")
@SecurityRequirement(name = "bearerAuth")
public class AddressController {
    private final AddressService service;
    public AddressController(AddressService service) { this.service = service; }

    @GetMapping
    @Operation(summary = "Danh sách địa chỉ của tôi, có phân trang")
    public ResponseEntity<AddressPage> list(JwtAuthenticationToken auth,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(userId(auth), page, pageSize));
    }

    @PostMapping
    @Operation(summary = "Thêm địa chỉ của tôi")
    public ResponseEntity<AddressView> create(JwtAuthenticationToken auth, @Valid @RequestBody AddressInput input) {
        var result = service.create(userId(auth), input);
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(result.id()).toUri();
        return ResponseEntity.created(location).cacheControl(CacheControl.noStore()).body(result);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Thay thế địa chỉ của tôi")
    public ResponseEntity<AddressView> update(JwtAuthenticationToken auth, @PathVariable UUID id,
                                              @Valid @RequestBody AddressInput input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.update(userId(auth), id, input));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Xóa địa chỉ khỏi sổ; giữ dữ liệu tham chiếu lịch sử")
    public ResponseEntity<Void> delete(JwtAuthenticationToken auth, @PathVariable UUID id) {
        service.delete(userId(auth), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private static UUID userId(JwtAuthenticationToken auth) { return UUID.fromString(auth.getName()); }
}
