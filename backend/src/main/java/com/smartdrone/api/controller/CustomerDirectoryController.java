package com.smartdrone.api.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartdrone.application.customers.CustomerDirectoryService;
import com.smartdrone.application.customers.CustomerDirectoryService.CustomerPage;
import com.smartdrone.application.customers.CustomerDirectoryService.CustomerView;
import com.smartdrone.application.customers.CustomerDirectoryService.UpdateCustomerRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/customers")
@PreAuthorize("hasRole('DISPATCHER')")
@Tag(name = "Customers", description = "FR-03: tra cứu và cập nhật thông tin khách hàng (điều phối viên)")
@SecurityRequirement(name = "bearerAuth")
public class CustomerDirectoryController {
    private final CustomerDirectoryService service;

    public CustomerDirectoryController(CustomerDirectoryService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Danh sách khách hàng, tìm theo tên/email/số điện thoại")
    public ResponseEntity<CustomerPage> list(@RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "10") int pageSize) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.list(search, page, pageSize));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Sửa họ tên và số điện thoại khách hàng; không sửa email, quyền hoặc trạng thái")
    public ResponseEntity<CustomerView> update(@PathVariable UUID id,
            @Valid @RequestBody UpdateCustomerRequest request, JwtAuthenticationToken auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.update(UUID.fromString(auth.getName()), id, request));
    }
}
