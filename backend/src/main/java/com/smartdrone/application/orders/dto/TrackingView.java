package com.smartdrone.application.orders.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.smartdrone.domain.enums.ChangeSource;
import com.smartdrone.domain.enums.OrderAction;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.domain.enums.StationStatus;

/**
 * 4.4: hợp đồng theo dõi cho web (4.4/4.3) và mobile (6.3).
 * Không có actorId/số điện thoại/địa chỉ nhận: khách và nhân viên đều xem được cùng DTO này.
 * timeline luôn tăng dần theo orderVersion (mỗi version đúng một dòng) nên client có thể
 * khử trùng/sắp lại bằng orderVersion.
 */
public record TrackingView(UUID orderId, long version, OrderStatus status, String statusLabel,
                           boolean terminal, Attempt attempt, StationPoint pickup, StationPoint dropoff,
                           MapInfo map, Position position, List<TimelineEntry> timeline, List<OrderAction> allowedActions,
                           Instant generatedAt) {

    public record Attempt(UUID id, int attemptNo, Instant slotStart, Instant slotEnd) { }

    /** latitude/longitude null nghĩa là thiếu hoặc không hợp lệ: client phải hiện "chưa có toạ độ". */
    public record StationPoint(UUID id, String name, Double latitude, Double longitude, StationStatus status) { }

    /**
     * mode luôn là STATIONS_ONLY: backend CHƯA có GPS drone thật. liveGpsAvailable=false để client
     * không được vẽ vị trí drone như dữ liệu thật; nếu mô phỏng thì phải ghi rõ "mô phỏng".
     */
    public record MapInfo(String mode, boolean liveGpsAvailable, String note) { }

    public enum Phase { NOT_STARTED, AT_PICKUP, IN_FLIGHT, AT_DROPOFF, UNKNOWN }

    /** Vị trí SUY RA từ trạng thái đơn, không phải GPS. simulated=true khi đang bay: điểm giữa tuyến chỉ để minh họa. */
    public record Position(Phase phase, boolean simulated) {
        public static Position of(OrderStatus status) {
            return switch (status) {
                case PENDING, APPROVED -> new Position(Phase.NOT_STARTED, false);
                case SCHEDULED, PREPARING -> new Position(Phase.AT_PICKUP, false);
                case IN_TRANSIT -> new Position(Phase.IN_FLIGHT, true);
                case AWAITING_PICKUP, DELIVERED -> new Position(Phase.AT_DROPOFF, false);
                case REJECTED, CANCELLED, FAILED, RETURNING, RETURNED -> new Position(Phase.UNKNOWN, false);
            };
        }
    }

    public record TimelineEntry(UUID eventId, long orderVersion, OrderStatus fromStatus, OrderStatus toStatus,
                                String toStatusLabel, OrderAction action, RoleName actorRole,
                                ChangeSource source, String reason, UUID attemptId, Integer attemptNo,
                                Instant occurredAt) { }
}
