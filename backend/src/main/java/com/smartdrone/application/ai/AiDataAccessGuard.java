package com.smartdrone.application.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.orders.OrderQueryService;
import com.smartdrone.application.orders.dto.OrderDetail;
import com.smartdrone.application.statistics.DateBasis;
import com.smartdrone.application.statistics.StatisticsService;
import com.smartdrone.domain.enums.RoleName;

import tools.jackson.databind.ObjectMapper;

/**
 * Cổng truy cập dữ liệu đơn của module AI (7.5 dùng OperationsDataReader qua StatisticsService): kiểm vai trò theo tác vụ và phạm vi đơn/trạm TRƯỚC khi lấy dữ liệu,
 * rồi chỉ trả về dữ liệu tối thiểu (không email, SĐT, địa chỉ chi tiết, tên người nhận, mã nhận hàng).
 * Chỉ đi qua OrderQueryService (khách chỉ đơn của mình, nhân viên theo trạm) và StatisticsService (số liệu tổng hợp);
 * không chạm repository/entity. Đơn ngoài phạm vi và đơn không tồn tại cùng trả 404 (quy ước của repo).
 */
@Component
public class AiDataAccessGuard {
    private final OrderQueryService orders;
    private final StatisticsService statistics;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiDataAccessGuard(OrderQueryService orders, StatisticsService statistics) {
        this.orders = orders;
        this.statistics = statistics;
    }

    /** Vai trò không được phép làm tác vụ này -> 403. */
    public void requireTask(AiTask task, RoleName role) {
        if (!task.isAllowedFor(role)) throw new ApiException(ErrorCode.FORBIDDEN);
    }

    /** Dữ liệu tối thiểu của MỘT đơn mà người dùng được xem; ngoài phạm vi -> 404. */
    @PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR')")
    public List<AiDataBlock> orderBlocks(AiTask task, UUID userId, RoleName role, UUID orderId) {
        requireTask(task, role);
        OrderDetail o = orders.get(userId, role, orderId);
        StringBuilder sb = new StringBuilder();
        sb.append("status: ").append(o.status()).append(" (").append(o.statusLabel()).append(")\n");
        sb.append("createdAt: ").append(o.createdAt()).append('\n');
        sb.append("updatedAt: ").append(o.updatedAt()).append('\n');
        sb.append("pickupStationId: ").append(o.pickupStationId()).append('\n');
        sb.append("dropoffStationId: ").append(o.dropoffStationId()).append('\n');
        if (o.packageInfo() != null) {
            sb.append("packageWeightGrams: ").append(o.packageInfo().weightGrams()).append('\n');
            sb.append("packageSizeCm: ").append(o.packageInfo().lengthCm()).append('x')
                    .append(o.packageInfo().widthCm()).append('x').append(o.packageInfo().heightCm()).append('\n');
        }
        if (o.history() != null) {
            sb.append("history:\n");
            o.history().forEach(h -> sb.append("- ").append(h.toStatus()).append(" at ").append(h.occurredAt()).append('\n'));
        }
        sb.append("now: ").append(Instant.now()).append('\n');
        return List.of(new AiDataBlock("order", sb.toString()));
    }

    /** 7.3: chi tiết MỘT đơn (kèm timeline) mà người dùng được xem; ngoài phạm vi -> 404. Chỉ service tóm tắt dùng, nó tự lọc trường trước khi tạo prompt. */
    @PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR')")
    public OrderDetail orderForSummary(UUID userId, RoleName role, UUID orderId) {
        requireTask(AiTask.ORDER_SUMMARY, role);
        return orders.get(userId, role, orderId);
    }

    /** Số liệu tổng hợp theo bộ lọc thống kê 5.1 (chỉ LOGISTICS_MANAGER, phạm vi trạm do StatisticsService áp). */
    @PreAuthorize("hasRole('LOGISTICS_MANAGER')")
    public List<AiDataBlock> analyticsBlocks(UUID userId, RoleName role, java.time.LocalDate from,
                                             java.time.LocalDate to, DateBasis basis, UUID stationId) {
        requireTask(AiTask.OPS_ANALYSIS, role);
        var summary = statistics.summary(userId, statistics.period(from, to, basis, stationId));
        return List.of(new AiDataBlock("statistics_summary", mapper.writeValueAsString(summary)));
    }
}
