package com.smartdrone.application.dispatch.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Kế hoạch điều phối drone của một đơn. Mọi mốc thời gian là ước tính theo công thức (source = FORMULA).
 * Khách hàng chỉ nhận phần không lộ vận hành: drone, trạm xuất phát của drone và thiết bị sạc để null.
 *
 * stage: NONE (chưa tới bước điều phối), PLANNED (xem trước, chưa phân công), ASSIGNED, IN_FLIGHT,
 * COMPLETED, NO_DRONE (chưa có drone phù hợp), NO_CHARGER (trạm đích chưa có chỗ sạc nhận được).
 */
public record DispatchView(UUID orderId, String orderStatus, String stage, boolean assigned, Long waitMinutes,
                           String waitReason, String message, Instant plannedDepartureAt, Instant plannedArrivalAt,
                           String droneCode, String originStationName, Double repositionKm,
                           String reservedChargerCode, String source, Instant generatedAt) { }
