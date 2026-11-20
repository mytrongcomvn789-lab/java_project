package com.smartdrone.application.admin;

import static com.smartdrone.domain.enums.RoleName.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.smartdrone.domain.enums.RoleName;

// Danh mục quyền chức năng và quyền mặc định của từng vai trò (FR-02).
// GHI CHÚ: hiện các endpoint nghiệp vụ vẫn kiểm tra theo VAI TRÒ (RbacPolicy + @PreAuthorize).
// Bảng quyền này là nguồn cấu hình để nối dần vào kiểm tra quyền ở các task sau.
public final class PermissionCatalog {
    private PermissionCatalog() { }

    public record Def(String code, String group, String label) { }

    public static final List<Def> ALL = List.of(
            new Def("ACCOUNT_MANAGE", "Quản trị hệ thống", "Quản lý tài khoản"),
            new Def("ROLE_MANAGE", "Quản trị hệ thống", "Quản lý vai trò & quyền"),
            new Def("SETTINGS_MANAGE", "Quản trị hệ thống", "Cấu hình hệ thống"),
            new Def("AUDIT_VIEW", "Quản trị hệ thống", "Xem Audit Log"),
            new Def("STATION_VIEW", "Trạm", "Xem danh sách trạm"),
            new Def("STATION_MONITOR", "Trạm", "Giám sát trạm"),
            new Def("STATION_MANAGE", "Trạm", "Quản lý thông tin trạm"),
            new Def("STATION_STATUS_UPDATE", "Trạm", "Đổi trạng thái vận hành trạm"),
            new Def("ORDER_CREATE", "Đơn giao hàng", "Tạo yêu cầu giao hàng"),
            new Def("ORDER_VIEW", "Đơn giao hàng", "Xem đơn giao hàng"),
            new Def("ORDER_REVIEW", "Đơn giao hàng", "Duyệt / từ chối yêu cầu"),
            new Def("ORDER_SCHEDULE", "Đơn giao hàng", "Lên lịch giao hàng"),
            new Def("ORDER_RECEIVE", "Đơn giao hàng", "Tiếp nhận kiện hàng tại trạm"),
            new Def("ADDRESS_MANAGE", "Khách hàng", "Quản lý địa chỉ giao hàng"),
            new Def("REPORT_VIEW", "Báo cáo", "Xem thống kê & báo cáo"));

    public static final Map<RoleName, Set<String>> DEFAULTS = defaults();

    private static Map<RoleName, Set<String>> defaults() {
        Map<RoleName, Set<String>> m = new LinkedHashMap<>();
        m.put(CUSTOMER, Set.of("ORDER_CREATE", "ORDER_VIEW", "ADDRESS_MANAGE", "STATION_VIEW"));
        m.put(DISPATCHER, Set.of("ORDER_VIEW", "ORDER_REVIEW", "ORDER_SCHEDULE", "STATION_VIEW", "STATION_MONITOR"));
        m.put(STATION_OPERATOR, Set.of("ORDER_VIEW", "ORDER_RECEIVE", "STATION_VIEW", "STATION_MONITOR",
                "STATION_STATUS_UPDATE"));
        m.put(LOGISTICS_MANAGER, Set.of("ORDER_VIEW", "STATION_VIEW", "STATION_MONITOR", "STATION_MANAGE",
                "STATION_STATUS_UPDATE", "REPORT_VIEW"));
        return m;
    }

    public static String displayName(RoleName role) {
        return switch (role) {
            case CUSTOMER -> "Khách hàng";
            case DISPATCHER -> "Điều phối viên";
            case STATION_OPERATOR -> "Nhân viên trạm";
            case LOGISTICS_MANAGER -> "Quản lý hậu cần";
            case SYSTEM_ADMIN -> "Quản trị viên";
        };
    }

    public static String description(RoleName role) {
        return switch (role) {
            case CUSTOMER -> "Tạo yêu cầu, theo dõi và xác nhận nhận hàng";
            case DISPATCHER -> "Phê duyệt, lên lịch và xử lý giao hàng thất bại";
            case STATION_OPERATOR -> "Kiểm tra và tiếp nhận kiện hàng tại trạm";
            case LOGISTICS_MANAGER -> "Dashboard, báo cáo và hiệu suất vận hành";
            case SYSTEM_ADMIN -> "Tài khoản, vai trò, cấu hình và audit log";
        };
    }
}
