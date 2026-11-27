package com.smartdrone.api.security;

import static com.smartdrone.domain.enums.RoleName.*;
import static org.springframework.http.HttpMethod.*;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpMethod;

import com.smartdrone.domain.enums.RoleName;

/** APDDMP-18: quyền theo method + path, đối chiếu APDDMP-9.
 * Quyền trên tài nguyên (chủ đơn, trạm được phân công) phải kiểm tra thêm tại service nghiệp vụ.
 * Khai báo route ở đây KHÔNG tạo controller hay hoàn thành nghiệp vụ của giai đoạn 3–5.
 */
public final class RbacPolicy {
    private RbacPolicy() { }

    public record Rule(HttpMethod method, String path, Set<RoleName> roles) {
        public Rule {
            roles = Set.copyOf(roles);
        }
    }

    private static Rule rule(HttpMethod method, String path, RoleName... roles) {
        return new Rule(method, path, Set.copyOf(Arrays.asList(roles)));
    }

    public static List<Rule> rules() {
        return RULES;
    }

    private static final List<Rule> RULES = List.of(
            rule(GET, "/v1/me/permissions", RoleName.values()),
            rule(GET, "/v1/me", RoleName.values()),
            rule(PATCH, "/v1/me", RoleName.values()),
            rule(POST, "/v1/me/password", RoleName.values()),
            rule(GET, "/v1/customers", DISPATCHER),
            rule(PATCH, "/v1/customers/{id}", DISPATCHER),
            rule(GET, "/v1/roles", SYSTEM_ADMIN),
            rule(GET, "/v1/users", SYSTEM_ADMIN),
            rule(PATCH, "/v1/users/{id}/access", SYSTEM_ADMIN),
            rule(POST, "/v1/admin/api-keys", SYSTEM_ADMIN),
            rule(GET, "/v1/admin/api-keys", SYSTEM_ADMIN),
            rule(POST, "/v1/admin/api-keys/{id}/revoke", SYSTEM_ADMIN),
            rule(POST, "/v1/admin/api-keys/{id}/rotate", SYSTEM_ADMIN),
            rule(GET, "/v1/audit-events", SYSTEM_ADMIN, LOGISTICS_MANAGER), // APDDMP-3: quản lý hậu cần theo dõi nhật ký
            // FR-02: tài khoản, vai trò & quyền, cấu hình hệ thống (chỉ SYSTEM_ADMIN).
            rule(GET, "/v1/admin/accounts", SYSTEM_ADMIN),
            rule(POST, "/v1/admin/accounts", SYSTEM_ADMIN),
            rule(PUT, "/v1/admin/accounts/{id}", SYSTEM_ADMIN),
            rule(PATCH, "/v1/admin/accounts/{id}/status", SYSTEM_ADMIN),
            rule(GET, "/v1/admin/roles", SYSTEM_ADMIN),
            rule(PUT, "/v1/admin/roles/{role}/permissions", SYSTEM_ADMIN),
            rule(GET, "/v1/admin/settings", SYSTEM_ADMIN),
            rule(PUT, "/v1/admin/settings", SYSTEM_ADMIN),

            rule(GET, "/v1/addresses", CUSTOMER),
            rule(POST, "/v1/addresses", CUSTOMER),
            rule(PUT, "/v1/addresses/{id}", CUSTOMER),
            rule(DELETE, "/v1/addresses/{id}", CUSTOMER),

            rule(GET, "/v1/stations", RoleName.values()),
            rule(GET, "/v1/stations/monitoring", LOGISTICS_MANAGER, DISPATCHER, STATION_OPERATOR),
            rule(POST, "/v1/stations", LOGISTICS_MANAGER),
            rule(PUT, "/v1/stations/{id}", LOGISTICS_MANAGER),
            rule(PATCH, "/v1/stations/{id}/status", STATION_OPERATOR, LOGISTICS_MANAGER),
            rule(GET, "/v1/stations/{id}/availability", DISPATCHER, STATION_OPERATOR, LOGISTICS_MANAGER),
            rule(POST, "/v1/orders", CUSTOMER),
            rule(GET, "/v1/orders", CUSTOMER, DISPATCHER, STATION_OPERATOR, LOGISTICS_MANAGER),
            rule(GET, "/v1/orders/{id}", CUSTOMER, DISPATCHER, STATION_OPERATOR, LOGISTICS_MANAGER),
            rule(PUT, "/v1/orders/{id}", CUSTOMER),
            rule(POST, "/v1/orders/{id}/cancel", CUSTOMER),
            rule(POST, "/v1/orders/{id}/approve", DISPATCHER),
            rule(POST, "/v1/orders/{id}/reject", DISPATCHER),
            rule(POST, "/v1/orders/{id}/fail", DISPATCHER),
            rule(POST, "/v1/orders/{id}/return", DISPATCHER),
            rule(POST, "/v1/orders/{id}/schedules", DISPATCHER),
            rule(POST, "/v1/orders/{id}/receipts", STATION_OPERATOR),
            rule(GET, "/v1/station-receipts", STATION_OPERATOR),
            rule(GET, "/v1/orders/{id}/receipt-context", STATION_OPERATOR),
            rule(POST, "/v1/orders/{id}/dispatch", STATION_OPERATOR),
            rule(POST, "/v1/orders/{id}/arrive", STATION_OPERATOR),
            rule(POST, "/v1/orders/{id}/complete-return", STATION_OPERATOR),
            rule(POST, "/v1/orders/{id}/confirmations", STATION_OPERATOR),
            rule(GET, "/v1/orders/{id}/tracking", CUSTOMER, DISPATCHER, STATION_OPERATOR, LOGISTICS_MANAGER),
            rule(GET, "/v1/orders/{id}/eta", CUSTOMER, DISPATCHER, STATION_OPERATOR, LOGISTICS_MANAGER),
            rule(GET, "/v1/orders/{id}/summary", CUSTOMER, DISPATCHER, LOGISTICS_MANAGER),
            rule(GET, "/v1/orders/{id}/pickup-code", CUSTOMER),
            rule(GET, "/v1/orders/{id}/confirmation", CUSTOMER, DISPATCHER, STATION_OPERATOR, LOGISTICS_MANAGER),
            rule(GET, "/v1/notifications", CUSTOMER),
            rule(POST, "/v1/notifications/{id}/read", CUSTOMER),
            rule(POST, "/v1/notifications/read-all", CUSTOMER),
            rule(POST, "/v1/assistant/messages", CUSTOMER),
            rule(GET, "/v1/analytics/deliveries", LOGISTICS_MANAGER),
            // 7.1: AI Service (hợp đồng nháp). Chỉ JWT người dùng; service còn kiểm phạm vi đơn/trạm.
            rule(GET, "/v1/ai/orders/{id}/eta", CUSTOMER, DISPATCHER, STATION_OPERATOR),
            rule(POST, "/v1/ai/orders/{id}/summary", CUSTOMER, DISPATCHER, STATION_OPERATOR),
            rule(POST, "/v1/ai/chat", CUSTOMER),
            rule(POST, "/v1/ai/analytics", LOGISTICS_MANAGER),
            // 5.1: thống kê đơn, tỷ lệ giao, tải trạm (service còn @PreAuthorize và lọc theo trạm được phân công).
            rule(GET, "/v1/statistics/orders/summary", LOGISTICS_MANAGER),
            rule(GET, "/v1/statistics/orders/daily", LOGISTICS_MANAGER),
            rule(GET, "/v1/statistics/orders/export.xlsx", LOGISTICS_MANAGER),
            rule(GET, "/v1/statistics/orders/export.pdf", LOGISTICS_MANAGER),
            rule(GET, "/v1/statistics/stations/load", LOGISTICS_MANAGER),
            rule(POST, "/v1/files/upload-tickets", CUSTOMER, STATION_OPERATOR),
            rule(POST, "/v1/files/upload-tickets", CUSTOMER, STATION_OPERATOR),
            rule(POST, "/v1/files/{id}/complete", CUSTOMER, STATION_OPERATOR),
            rule(GET, "/v1/files/{id}/download-link", CUSTOMER, STATION_OPERATOR, DISPATCHER),
            rule(GET, "/v1/files/{id}/download-link", CUSTOMER, STATION_OPERATOR, DISPATCHER)
    );
    // /integration/** chưa có xác thực service-to-service: mặc định bị từ chối.
    // SYSTEM_ADMIN không được kế thừa quyền nghiệp vụ của 4 actor còn lại.
}
