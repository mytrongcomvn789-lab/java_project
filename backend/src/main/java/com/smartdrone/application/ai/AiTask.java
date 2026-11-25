package com.smartdrone.application.ai;

import java.util.Set;

import com.smartdrone.domain.enums.RoleName;

/** Loại tác vụ AI. Mỗi tác vụ có tập vai trò được phép; đây là lớp kiểm quyền thứ ba sau RbacPolicy và @PreAuthorize. */
public enum AiTask {
    /** 7.2: ước lượng thời gian giao. */
    ETA(Set.of(RoleName.CUSTOMER, RoleName.DISPATCHER, RoleName.STATION_OPERATOR)),
    /** 7.3: tóm tắt đơn hàng. */
    ORDER_SUMMARY(Set.of(RoleName.CUSTOMER, RoleName.DISPATCHER, RoleName.STATION_OPERATOR)),
    /** 7.4: trợ lý hỏi đáp. */
    CHAT(Set.of(RoleName.CUSTOMER, RoleName.DISPATCHER, RoleName.STATION_OPERATOR)),
    /** 7.5/7.6: phân tích vận hành, chỉ trên số liệu tổng hợp của thống kê 5.1. */
    OPS_ANALYSIS(Set.of(RoleName.LOGISTICS_MANAGER));

    private final Set<RoleName> allowedRoles;

    AiTask(Set<RoleName> allowedRoles) {
        this.allowedRoles = allowedRoles;
    }

    public boolean isAllowedFor(RoleName role) {
        return allowedRoles.contains(role);
    }
}
