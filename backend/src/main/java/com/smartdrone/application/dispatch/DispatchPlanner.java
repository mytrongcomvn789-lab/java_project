package com.smartdrone.application.dispatch;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.smartdrone.application.eta.GeoDistance;

/**
 * Chọn drone và thiết bị sạc ở trạm đích cho một đơn. Hàm thuần (không đọc DB, không ghi gì) nên test được.
 *
 * Quy tắc theo quy trình nghiệp vụ:
 *  - Drone dùng chung: ưu tiên drone đang ở trạm gửi; nếu không có thì lấy drone ở trạm gần nhất (xa dần),
 *    drone đó phải bay tới trạm gửi trước.
 *  - Drone phải đủ pin cho cả chặng (tới trạm gửi + giao) và còn dự phòng. Drone đang sạc chưa đủ pin thì chờ sạc.
 *  - Trạm đích phải có thiết bị sạc tiếp nhận được; được phép đặt trước thiết bị sắp trống,
 *    drone không cần đợi thiết bị trống mới khởi hành mà chỉ cần đến sau lúc nó được giải phóng.
 *
 * Mọi con số là ƯỚC TÍNH: khoảng cách đường chim bay, tốc độ trung bình, mức tiêu hao pin và tốc độ sạc là giả định.
 */
public final class DispatchPlanner {
    /** Pin tiêu hao mỗi km bay (giả định demo, chưa hiệu chuẩn). */
    public static final double DRAIN_PERCENT_PER_KM = 2.0;
    /** Pin dự phòng tối thiểu khi đến nơi. */
    public static final int RESERVE_PERCENT = 20;
    /** Khoảng đệm sau khi thiết bị sạc được giải phóng trước khi nhận drone khác. */
    public static final int RELEASE_MARGIN_MINUTES = 5;
    /** Tốc độ sạc giả lập (phần trăm pin mỗi phút), cùng nguồn với ChargingRules. */
    public static final double CHARGE_PERCENT_PER_MINUTE = 1.0;

    public static final String WAIT_NONE = "NONE";
    public static final String WAIT_DRONE_CHARGING = "DRONE_CHARGING";
    public static final String WAIT_DRONE_REPOSITION = "DRONE_REPOSITION";
    public static final String WAIT_DESTINATION_FULL = "DESTINATION_FULL";

    private DispatchPlanner() { }

    public record Geo(double lat, double lon) {
        double km(Geo other) { return GeoDistance.haversineKm(lat, lon, other.lat, other.lon); }
    }

    /** Drone có thể nhận việc. batteryNow là pin hiện tại; onCharger = đang cắm vào thiết bị sạc. */
    public record DroneCandidate(UUID droneId, String code, UUID stationId, Geo at, int batteryNow,
                                 boolean onCharger) { }

    /** Thiết bị sạc ở trạm đích. freeAt = lúc dự kiến nhận được drone (đã gồm khoảng đệm); reserved = đã có đơn khác đặt. */
    public record DestinationDevice(UUID id, String code, boolean maintenance, boolean reserved, Instant freeAt) { }

    public enum Outcome { READY, WAITING, NO_DRONE, NO_CHARGER }

    public record Plan(Outcome outcome, DroneCandidate drone, UUID chargerId, String chargerCode, double repositionKm,
                       double flightKm, int consumptionPercent, int batteryAtDeparture, Instant departureAt,
                       Instant arrivalAt, long waitMinutes, String waitReason) {
        public boolean assignable() { return outcome == Outcome.READY || outcome == Outcome.WAITING; }

        static Plan none(Outcome outcome, double flightKm) {
            return new Plan(outcome, null, null, null, 0, flightKm, 0, 0, null, null, 0, WAIT_NONE);
        }
    }

    public static Plan plan(UUID pickupStationId, Geo pickup, Geo dropoff, List<DroneCandidate> drones,
            List<DestinationDevice> devices, Instant now, double speedKmh) {
        double flightKm = pickup.km(dropoff);
        long flightMinutes = minutesFor(flightKm, speedKmh);

        record Option(DroneCandidate drone, double repKm, int needed, Instant ready, Instant departure,
                      boolean charging, boolean repositioning) { }

        Optional<Option> best = drones.stream().map(d -> {
            double repKm = d.stationId().equals(pickupStationId) ? 0 : d.at().km(pickup);
            int needed = (int) Math.ceil((repKm + flightKm) * DRAIN_PERCENT_PER_KM) + RESERVE_PERCENT;
            if (needed > 100) return null;
            Instant ready = now;
            boolean charging = false;
            if (d.batteryNow() < needed) {
                if (!d.onCharger()) return null; // đang đỗ mà thiếu pin: không có gì để chờ
                ready = now.plus(Duration.ofMinutes((long) Math.ceil((needed - d.batteryNow()) / CHARGE_PERCENT_PER_MINUTE)));
                charging = true;
            }
            Instant departure = ready.plus(Duration.ofMinutes(minutesFor(repKm, speedKmh)));
            return new Option(d, repKm, needed, ready, departure, charging, repKm > 0);
        }).filter(java.util.Objects::nonNull)
          .min(Comparator.comparing(Option::departure).thenComparingDouble(Option::repKm)
                  .thenComparing(o -> o.drone().code()));

        if (best.isEmpty()) return Plan.none(Outcome.NO_DRONE, flightKm);
        Option o = best.get();

        // Thiết bị tiếp nhận: không bảo trì, chưa bị đặt; ưu tiên cái nhận được sớm nhất.
        Optional<DestinationDevice> device = devices.stream().filter(x -> !x.maintenance() && !x.reserved())
                .min(Comparator.comparing(DestinationDevice::freeAt).thenComparing(DestinationDevice::code));
        if (device.isEmpty()) return Plan.none(Outcome.NO_CHARGER, flightKm);
        DestinationDevice dev = device.get();

        Instant departure = o.departure();
        boolean destinationBlocks = false;
        Instant arrival = departure.plus(Duration.ofMinutes(flightMinutes));
        if (dev.freeAt().isAfter(arrival)) {
            // Không cần đợi thiết bị trống rồi mới đi: chỉ cần đến sau lúc nó được giải phóng.
            departure = dev.freeAt().minus(Duration.ofMinutes(flightMinutes));
            arrival = dev.freeAt();
            destinationBlocks = true;
        }
        long seconds = Math.max(0, Duration.between(now, departure).getSeconds());
        long wait = (seconds + 59) / 60;
        String reason = wait == 0 ? WAIT_NONE
                : destinationBlocks ? WAIT_DESTINATION_FULL
                : o.charging() ? WAIT_DRONE_CHARGING : WAIT_DRONE_REPOSITION;
        int consumption = (int) Math.ceil((o.repKm() + flightKm) * DRAIN_PERCENT_PER_KM);
        int battery = o.charging() ? Math.min(100, o.needed()) : o.drone().batteryNow();
        return new Plan(wait == 0 ? Outcome.READY : Outcome.WAITING, o.drone(), dev.id(), dev.code(), o.repKm(),
                flightKm, consumption, battery, departure, arrival, wait, reason);
    }

    private static long minutesFor(double km, double speedKmh) {
        return (long) Math.ceil(km / speedKmh * 60.0);
    }
}
