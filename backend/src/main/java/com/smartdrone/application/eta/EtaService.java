package com.smartdrone.application.eta;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import com.smartdrone.application.ai.AiResult;
import com.smartdrone.application.ai.AiSource;
import com.smartdrone.application.ai.AiUsage;
import com.smartdrone.application.orders.OrderTrackingService;
import com.smartdrone.application.orders.dto.TrackingView;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 7.2: ETA bản công thức cho một đơn. Quyền xem đơn do OrderTrackingService quyết định (cùng phạm vi GET /orders/{id};
 * đơn ngoài phạm vi hoặc không tồn tại -> 404). Không gọi AI/provider. Ước tính nghiệp vụ, không điều khiển drone.
 */
@Service
@PreAuthorize("hasAnyRole('CUSTOMER','DISPATCHER','STATION_OPERATOR')")
public class EtaService {
    /** Chỉ các trạng thái còn đang trên đường tới điểm giao mới có ETA. */
    static final Set<OrderStatus> APPLICABLE =
            Set.of(OrderStatus.SCHEDULED, OrderStatus.PREPARING, OrderStatus.IN_TRANSIT);

    private final OrderTrackingService tracking;
    private final EtaProperties props;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    public EtaService(OrderTrackingService tracking, EtaProperties props, Clock clock) {
        this.tracking = tracking;
        this.props = props;
        this.clock = clock;
    }

    public AiResult estimate(UUID actorId, RoleName role, UUID orderId) {
        TrackingView t = tracking.get(actorId, role, orderId); // 404 nếu ngoài phạm vi
        Instant now = clock.instant();
        ObjectNode r = mapper.createObjectNode();
        r.put("orderId", orderId.toString());
        r.put("status", t.status().name());
        r.put("method", EtaEstimate.METHOD);
        r.put("computedAt", now.toString());
        if (!APPLICABLE.contains(t.status())) {
            r.put("applicable", false);
            r.put("computed", false);
            r.put("reason", "STATUS_NOT_APPLICABLE");
            r.putNull("etaAt");
            r.putNull("etaMinutes");
            return wrap(r, now);
        }
        r.put("applicable", true);
        EtaEstimate e;
        if (t.status() == OrderStatus.IN_TRANSIT) {
            Instant departed = t.timeline().stream().filter(x -> x.toStatus() == OrderStatus.IN_TRANSIT)
                    .map(TrackingView.TimelineEntry::occurredAt).reduce((a, b) -> b).orElse(null);
            e = EtaCalculator.estimateInFlight(t.pickup().latitude(), t.pickup().longitude(),
                    t.dropoff().latitude(), t.dropoff().longitude(), props.speedKmh(), departed, now);
        } else {
            Instant slotStart = t.attempt() == null ? null : t.attempt().slotStart();
            e = EtaCalculator.estimate(t.pickup().latitude(), t.pickup().longitude(),
                    t.dropoff().latitude(), t.dropoff().longitude(), props.speedKmh(),
                    props.stationWaitMinutes(), slotStart, now);
        }
        if (!e.computed()) {
            r.put("computed", false);
            r.put("reason", e.reason());
            r.putNull("etaAt");
            r.putNull("etaMinutes");
            return wrap(r, now);
        }
        r.put("computed", true);
        r.put("etaAt", e.etaAt().toString());
        r.put("etaMinutes", e.minutesFromNow());
        r.put("travelMinutes", e.travelMinutes());
        r.put("waitMinutes", e.waitMinutes());
        r.put("distanceKm", Math.round(e.distanceKm() * 100.0) / 100.0);
        // Độ tin cậy cố định LOW: tham số chưa hiệu chuẩn, không có GPS drone. Không phải xác suất.
        r.put("confidence", "LOW");
        var lim = r.putArray("limitations");
        e.limitations().forEach(lim::add);
        return wrap(r, now);
    }

    private static AiResult wrap(ObjectNode result, Instant now) {
        return new AiResult(result, null, false, false, AiSource.FORMULA, AiUsage.NONE, UUID.randomUUID(), now, null);
    }
}
