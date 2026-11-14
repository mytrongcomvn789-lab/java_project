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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.common.IfMatchVersion;
import com.smartdrone.application.orders.OrderCreationFacade;
import com.smartdrone.application.orders.OrderQueryService;
import com.smartdrone.application.orders.OrderService;
import com.smartdrone.application.orders.dto.OrderCreation;
import com.smartdrone.application.orders.dto.OrderDetail;
import com.smartdrone.application.orders.dto.OrderListQuery;
import com.smartdrone.application.orders.dto.OrderPage;
import com.smartdrone.application.orders.dto.OrderRequest;
import com.smartdrone.application.orders.dto.OrderView;
import com.smartdrone.domain.enums.RoleName;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/v1/orders")
@Tag(name = "Orders", description = "Đơn giao hàng: khách tạo, sửa, hủy khi còn chờ duyệt; xem danh sách và chi tiết theo phạm vi")
@SecurityRequirement(name = "bearerAuth")
public class OrderController {

    private final OrderCreationFacade creation;
    private final OrderService service;
    private final OrderQueryService queries;

    public OrderController(OrderCreationFacade creation, OrderService service,
                           OrderQueryService queries) {
        this.creation = creation;
        this.service = service;
        this.queries = queries;
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

    @GetMapping
    @PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER')")
    @Operation(summary = "Danh sách đơn trong phạm vi của tôi (lọc, tìm kiếm, phân trang)")
    public ResponseEntity<OrderPage> list(JwtAuthenticationToken auth,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String stationId,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        OrderListQuery query = OrderListQuery.of(code, status, from, to, stationId, page, pageSize, sort);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(queries.list(userId(auth), roleOf(auth), query));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER')")
    @Operation(summary = "Chi tiết một đơn trong phạm vi của tôi")
    public ResponseEntity<OrderDetail> get(JwtAuthenticationToken auth, @PathVariable UUID id) {
        OrderDetail order = queries.get(userId(auth), roleOf(auth), id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ETAG, IfMatchVersion.format(order.version())).body(order);
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

    // Đọc vai trò từ token (authority dạng ROLE_CUSTOMER -> CUSTOMER).
    private static RoleName roleOf(JwtAuthenticationToken auth) {
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> RoleName.valueOf(a.substring(5)))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN));
    }
}