package com.smartdrone.api.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.common.IfMatchVersion;
import com.smartdrone.application.orders.OrderCreationFacade;
import com.smartdrone.application.orders.OrderService;
import com.smartdrone.application.orders.dto.OrderCreation;
import com.smartdrone.application.orders.dto.OrderRequest;
import com.smartdrone.application.orders.dto.OrderView;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/orders")
@Tag(name = "Orders", description = "Đơn giao hàng: khách tạo, sửa, hủy khi còn chờ duyệt")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    private final OrderCreationFacade creation;
    private final OrderService service;

    public OrderController(OrderCreationFacade creation, OrderService service) {
        this.creation = creation;
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Tạo đơn (bắt buộc header Idempotency-Key)")
    public ResponseEntity<OrderView> create(JwtAuthenticationToken auth,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderRequest request) {
        OrderCreation result = creation.create(userId(auth), idempotencyKey, request);
        OrderView order = result.order();
        if (result.replayed()) { // gửi lại cùng key + cùng nội dung: trả đơn cũ, không tạo mới
            return withOrderHeaders(ResponseEntity.ok(), order)
                    .header("Idempotent-Replayed", "true").body(order);
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(order.id()).toUri();
        return withOrderHeaders(ResponseEntity.created(location), order).body(order);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER')")
    @Operation(summary = "Xem một đơn (khách chỉ xem đơn của mình)")
    public ResponseEntity<OrderView> get(JwtAuthenticationToken auth, @PathVariable UUID id) {
        if (!isCustomer(auth)) {
            // Phạm vi dữ liệu của nhân viên (theo trạm/tổ chức) do 3.7 quyết định. Tới lúc đó, mặc định không thấy gì.
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        OrderView order = service.getOwn(userId(auth), id);
        return withOrderHeaders(ResponseEntity.ok(), order).body(order);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Sửa đơn đang chờ duyệt (bắt buộc If-Match)")
    public ResponseEntity<OrderView> update(JwtAuthenticationToken auth, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody OrderRequest request) {
        long expectedVersion = IfMatchVersion.parse(ifMatch); // thiếu header -> 428
        OrderView order = service.update(userId(auth), id, expectedVersion, request);
        return withOrderHeaders(ResponseEntity.ok(), order).body(order);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Hủy đơn đang chờ duyệt (bắt buộc If-Match)")
    public ResponseEntity<OrderView> cancel(JwtAuthenticationToken auth, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        long expectedVersion = IfMatchVersion.parse(ifMatch);
        OrderView order = service.cancel(userId(auth), id, expectedVersion);
        return withOrderHeaders(ResponseEntity.ok(), order).body(order);
    }

    private static ResponseEntity.BodyBuilder withOrderHeaders(ResponseEntity.BodyBuilder builder, OrderView order) {
        return builder.cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ETAG, IfMatchVersion.format(order.version()));
    }

    private static UUID userId(JwtAuthenticationToken auth) {
        return UUID.fromString(auth.getName());
    }

    private static boolean isCustomer(JwtAuthenticationToken auth) {
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()));
    }
}