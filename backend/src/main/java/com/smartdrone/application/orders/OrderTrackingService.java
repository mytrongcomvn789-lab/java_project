package com.smartdrone.application.orders;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.orders.dto.TrackingView;
import com.smartdrone.application.orders.scope.OrderScopeResolver;
import com.smartdrone.application.orders.scope.OrderSpecs;
import com.smartdrone.application.stations.StaffStationScope;
import com.smartdrone.domain.entity.DeliveryAttempt;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.entity.OrderStatusHistory;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.enums.AttemptStatus;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.DeliveryAttemptRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.OrderStatusHistoryRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;

/**
 * 4.4: truy vấn timeline/theo dõi. Nguồn duy nhất của timeline là order_status_history (3.5):
 * không có bảng/nguồn trạng thái thứ hai. Phạm vi xem đơn giống 3.7 (GET /orders/{id}).
 */
@Service
@PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR','LOGISTICS_MANAGER')")
public class OrderTrackingService {
    private final OrderRepository orders;
    private final OrderStatusHistoryRepository history;
    private final DeliveryAttemptRepository attempts;
    private final StationRepository stations;
    private final OrderScopeResolver scopes;
    private final StaffStationScope staffScope;
    private final Clock clock;

    public OrderTrackingService(OrderRepository orders, OrderStatusHistoryRepository history,
            DeliveryAttemptRepository attempts, StationRepository stations, OrderScopeResolver scopes,
            StaffStationScope staffScope, Clock clock) {
        this.orders = orders; this.history = history; this.attempts = attempts; this.stations = stations;
        this.scopes = scopes; this.staffScope = staffScope; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TrackingView get(UUID actorId, RoleName role, UUID orderId) {
        var scope = scopes.resolve(actorId, role);
        Order order = orders.findOne(OrderSpecs.inScope(scope).and(OrderSpecs.hasId(orderId)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));

        List<DeliveryAttempt> all = attempts.findByOrderIdOrderByAttemptNoAsc(orderId);
        Map<UUID, Integer> attemptNos = new HashMap<>();
        all.forEach(a -> attemptNos.put(a.getId(), a.getAttemptNo()));
        var timeline = history.findByOrderIdOrderByOrderVersionAsc(orderId).stream()
                .map(h -> entry(h, attemptNos, role)).toList();

        DeliveryAttempt active = all.stream().filter(a -> a.getStatus() == AttemptStatus.ACTIVE)
                .reduce((a, b) -> b).orElse(null);

        Map<UUID, Station> byId = new HashMap<>();
        stations.findAllById(Set.of(order.getPickupStationId(), order.getDropoffStationId()))
                .forEach(s -> byId.put(s.getId(), s));

        return new TrackingView(order.getId(), order.getVersion(), order.getStatus(),
                order.getStatus().label(), order.getStatus().isTerminal(),
                active == null ? null : new TrackingView.Attempt(active.getId(), active.getAttemptNo(),
                        active.getSlotStart(), active.getSlotEnd()),
                point(order.getPickupStationId(), byId.get(order.getPickupStationId())),
                point(order.getDropoffStationId(), byId.get(order.getDropoffStationId())),
                new TrackingView.MapInfo("STATIONS_ONLY", false,
                        "Chưa có GPS drone thật: bản đồ chỉ hiển thị vị trí hai trạm."),
                TrackingView.Position.of(order.getStatus()), timeline, allowedActions(actorId, role, order), Instant.now(clock));
    }

    private static TrackingView.TimelineEntry entry(OrderStatusHistory h, Map<UUID, Integer> attemptNos, RoleName role) {
        // Ghi chú nội bộ của nhân viên không gửi cho khách; khách chỉ thấy lý do từ chối/thất bại.
        boolean showReason = role != RoleName.CUSTOMER || h.getAction() == OrderAction.REJECT || h.getAction() == OrderAction.FAIL;
        return new TrackingView.TimelineEntry(h.getId(), h.getOrderVersion(), h.getFromStatus(),
                h.getToStatus(), h.getToStatus().label(), h.getAction(), h.getActorRole(), h.getSource(),
                showReason ? h.getReason() : null, h.getAttemptId(), h.getAttemptId() == null ? null : attemptNos.get(h.getAttemptId()),
                h.getOccurredAt());
    }

    /** Thiếu trạm hoặc toạ độ không hợp lệ (NaN, ngoài miền, hoặc đúng 0/0) → null, không đoán. */
    static TrackingView.StationPoint point(UUID id, Station s) {
        if (s == null) return new TrackingView.StationPoint(id, null, null, null, null);
        boolean valid = Double.isFinite(s.getLatitude()) && Double.isFinite(s.getLongitude())
                && Math.abs(s.getLatitude()) <= 90 && Math.abs(s.getLongitude()) <= 180
                && !(s.getLatitude() == 0 && s.getLongitude() == 0);
        return new TrackingView.StationPoint(id, s.getName(), valid ? s.getLatitude() : null,
                valid ? s.getLongitude() : null, s.getStatus());
    }

    /** Gợi ý cho UI (server vẫn kiểm lại khi ghi): chỉ gồm DISPATCH/ARRIVE của 4.4, đúng đầu trạm. */
    private List<OrderAction> allowedActions(UUID actorId, RoleName role, Order order) {
        if (role != RoleName.STATION_OPERATOR) return List.of();
        var mine = staffScope.stationIdsFor(actorId, RoleName.STATION_OPERATOR);
        if (order.getStatus() == OrderStatus.PREPARING && mine.contains(order.getPickupStationId())) {
            return List.of(OrderAction.DISPATCH);
        }
        if (order.getStatus() == OrderStatus.IN_TRANSIT && mine.contains(order.getDropoffStationId())) {
            return List.of(OrderAction.ARRIVE);
        }
        return List.of();
    }
}
