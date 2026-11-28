package com.smartdrone.application.dispatch;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.admin.AuditService;
import com.smartdrone.application.charging.ChargingRules;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.dispatch.DispatchPlanner.DestinationDevice;
import com.smartdrone.application.dispatch.DispatchPlanner.DroneCandidate;
import com.smartdrone.application.dispatch.DispatchPlanner.Geo;
import com.smartdrone.application.dispatch.DispatchPlanner.Outcome;
import com.smartdrone.application.dispatch.DispatchPlanner.Plan;
import com.smartdrone.application.dispatch.dto.DispatchBoardView;
import com.smartdrone.application.dispatch.dto.DispatchView;
import com.smartdrone.application.eta.EtaProperties;
import com.smartdrone.application.eta.GeoDistance;
import com.smartdrone.application.orders.scope.OrderScopeResolver;
import com.smartdrone.application.orders.scope.OrderSpecs;
import com.smartdrone.application.stations.StaffStationScope;
import com.smartdrone.domain.entity.ChargingDevice;
import com.smartdrone.domain.entity.Drone;
import com.smartdrone.domain.entity.DroneAssignment;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.enums.AssignmentStatus;
import com.smartdrone.domain.enums.ChargerState;
import com.smartdrone.domain.enums.DroneStatus;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.ChargingDeviceRepository;
import com.smartdrone.infrastructure.persistence.DroneAssignmentRepository;
import com.smartdrone.infrastructure.persistence.DroneRepository;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;

/**
 * Điều phối drone tự động: chọn drone (ưu tiên trạm gửi, rồi trạm gần nhất), đặt trước thiết bị sạc ở trạm đích,
 * tính thời gian chờ. Gắn vào luồng đơn bằng ba mốc: nhận kiện (PREPARING) → phân công, khởi hành → cất cánh,
 * đến trạm → hạ cánh và cắm sạc. Nếu hệ thống chưa có drone nào thì mọi hook không làm gì (giữ nguyên luồng cũ).
 */
@Service
public class DroneDispatchService {
    private static final Logger log = LoggerFactory.getLogger(DroneDispatchService.class);
    public static final String SOURCE = "FORMULA";
    private static final Set<AssignmentStatus> ACTIVE = EnumSet.of(AssignmentStatus.ASSIGNED, AssignmentStatus.IN_FLIGHT);
    private static final Set<OrderStatus> PLANNABLE = EnumSet.of(OrderStatus.SCHEDULED, OrderStatus.PREPARING);

    private final DroneRepository drones;
    private final ChargingDeviceRepository devices;
    private final DroneAssignmentRepository assignments;
    private final OrderRepository orders;
    private final StationRepository stations;
    private final OrderScopeResolver scopes;
    private final StaffStationScope staffScope;
    private final EtaProperties eta;
    private final AuditService audit;
    private final Clock clock;

    public DroneDispatchService(DroneRepository drones, ChargingDeviceRepository devices,
            DroneAssignmentRepository assignments, OrderRepository orders, StationRepository stations,
            OrderScopeResolver scopes, StaffStationScope staffScope, EtaProperties eta, AuditService audit,
            Clock clock) {
        this.drones = drones;
        this.devices = devices;
        this.assignments = assignments;
        this.orders = orders;
        this.stations = stations;
        this.scopes = scopes;
        this.staffScope = staffScope;
        this.eta = eta;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- đọc

    @Transactional(readOnly = true)
    public DispatchView view(UUID actorId, RoleName role, UUID orderId) {
        var scope = scopes.resolve(actorId, role);
        Order order = orders.findOne(OrderSpecs.inScope(scope).and(OrderSpecs.hasId(orderId)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        return viewOf(order, role != RoleName.CUSTOMER, Instant.now(clock), stationNames(List.of(order)));
    }

    @Transactional(readOnly = true)
    public DispatchBoardView board(UUID actorId, RoleName role) {
        Instant now = Instant.now(clock);
        var ids = staffScope.stationIdsFor(actorId, role);
        var all = new ArrayList<Order>();
        if (!ids.isEmpty()) {
            all.addAll(orders.findAll((root, q, cb) -> cb.and(
                    root.get("status").in(OrderStatus.SCHEDULED, OrderStatus.PREPARING, OrderStatus.IN_TRANSIT),
                    cb.or(root.get("pickupStationId").in(ids), root.get("dropoffStationId").in(ids)))));
        }
        var names = stationNames(all);
        var rows = all.stream()
                .sorted(java.util.Comparator.comparing(Order::getCreatedAt))
                .map(o -> new DispatchBoardView.Row(o.getId(), o.getStatus().name(),
                        names.getOrDefault(o.getPickupStationId(), "?"), names.getOrDefault(o.getDropoffStationId(), "?"),
                        viewOf(o, true, now, names)))
                .toList();

        var stationById = stations.findAll().stream().collect(Collectors.toMap(Station::getId, Station::getName));
        var fleet = drones.findAll().stream()
                .sorted(java.util.Comparator.comparing(Drone::getCode))
                .map(d -> new DispatchBoardView.FleetDrone(d.getId(), d.getCode(), d.getStatus().name(),
                        currentBattery(d, now), d.getStationId() == null ? null : stationById.get(d.getStationId())))
                .toList();
        return new DispatchBoardView(fleet, rows, SOURCE, now);
    }

    // ---------------------------------------------------------------- ghi (người dùng)

    /** Phân công (hoặc tính lại) drone cho đơn đang ở trạm gửi. Nhân viên trạm gửi của đơn mới được làm. */
    @Transactional
    public DispatchView assignManually(UUID actorId, UUID orderId) {
        var ids = staffScope.stationIdsFor(actorId, RoleName.STATION_OPERATOR);
        Order order = orders.findOne(OrderSpecs.hasId(orderId)).filter(o -> ids.contains(o.getPickupStationId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        if (order.getStatus() != OrderStatus.PREPARING) {
            throw new ApiException(ErrorCode.REQUEST_ERROR, "Chỉ điều phối drone cho đơn đã nhận kiện tại trạm gửi");
        }
        Instant now = Instant.now(clock);
        Optional<DroneAssignment> saved = assign(order, now);
        saved.ifPresent(a -> audit.record(actorId, "DRONE_ASSIGNED", "ORDER", orderId.toString(),
                "Điều phối drone cho đơn (chờ " + a.getWaitMinutes() + " phút)"));
        return viewOf(order, true, now, stationNames(List.of(order)));
    }

    // ---------------------------------------------------------------- hook vào luồng đơn

    /** Sau khi trạm gửi nhận kiện (đơn sang PREPARING): tự phân công nếu có drone. Không bao giờ làm hỏng luồng đơn. */
    @Transactional
    public void onReceived(Order order) {
        if (drones.count() == 0) return;
        try {
            assign(order, Instant.now(clock));
        } catch (RuntimeException e) {
            log.warn("Không tự phân công được drone cho đơn {}: {}", order.getId(), e.toString());
        }
    }

    /** Đơn khởi hành: drone đã phân công cất cánh, nhả thiết bị sạc đang cắm. */
    @Transactional
    public void onDispatched(Order order) {
        var assignment = assignments.findFirstByOrderIdAndStatusIn(order.getId(), EnumSet.of(AssignmentStatus.ASSIGNED));
        if (assignment.isEmpty()) return;
        Instant now = Instant.now(clock);
        var a = assignment.get();
        var drone = drones.findById(a.getDroneId()).orElse(null);
        if (drone == null) return;
        int battery = currentBattery(drone, now);
        devices.findByDroneId(drone.getId()).ifPresent(ChargingDevice::unplug);
        drone.takeOff(battery);
        a.markInFlight(now);
    }

    /** Đơn đến trạm nhận: drone hạ cánh, cắm vào thiết bị đã đặt trước (hoặc thiết bị trống khác, hoặc đỗ chờ). */
    @Transactional
    public void onArrived(Order order) {
        var assignment = assignments.findFirstByOrderIdAndStatusIn(order.getId(), EnumSet.of(AssignmentStatus.IN_FLIGHT));
        if (assignment.isEmpty()) return;
        Instant now = Instant.now(clock);
        var a = assignment.get();
        var drone = drones.findById(a.getDroneId()).orElse(null);
        if (drone == null) return;
        int landed = Math.max(0, drone.getBatteryPercent() - a.getConsumptionPercent());
        var atStation = devices.findByStationIdOrderByCode(order.getDropoffStationId());
        Optional<ChargingDevice> target = atStation.stream()
                .filter(d -> d.getId().equals(a.getReservedChargerId()) && isFree(d, now)).findFirst()
                .or(() -> atStation.stream().filter(d -> isFree(d, now)).findFirst());
        drone.land(order.getDropoffStationId(), landed);
        target.ifPresent(d -> {
            d.plug(drone.getId(), landed, now, 100);
            drone.startCharging();
        });
        a.complete(now);
    }

    // ---------------------------------------------------------------- tự động (định kỳ)

    /** Gán drone cho đơn PREPARING chưa có, tính lại kế hoạch của đơn đã gán, hủy kế hoạch của đơn không còn chờ. */
    @Transactional
    public void sweep() {
        if (drones.count() == 0) return;
        Instant now = Instant.now(clock);
        for (var a : assignments.findByStatusIn(EnumSet.of(AssignmentStatus.ASSIGNED))) {
            var order = orders.findById(a.getOrderId()).orElse(null);
            if (order == null || order.getStatus() != OrderStatus.PREPARING) a.cancel(now);
        }
        assignments.flush();
        var waiting = orders.findAll((root, q, cb) -> cb.equal(root.get("status"), OrderStatus.PREPARING));
        for (var order : waiting) {
            try {
                assign(order, now);
            } catch (RuntimeException e) {
                log.warn("Sweep điều phối lỗi với đơn {}: {}", order.getId(), e.toString());
            }
        }
    }

    // ---------------------------------------------------------------- nội bộ

    /** Tính kế hoạch và lưu. Đơn đã cất cánh thì giữ nguyên. Trả rỗng nếu chưa phân công được. */
    private Optional<DroneAssignment> assign(Order order, Instant now) {
        var existing = assignments.findFirstByOrderIdAndStatusIn(order.getId(), ACTIVE);
        if (existing.isPresent() && existing.get().getStatus() == AssignmentStatus.IN_FLIGHT) return existing;
        Plan plan = computePlan(order, now, existing.map(DroneAssignment::getId).orElse(null));
        if (plan == null || !plan.assignable()) {
            existing.ifPresent(a -> a.cancel(now)); // kế hoạch cũ không còn hợp lệ
            return Optional.empty();
        }
        var a = existing.orElseGet(() -> new DroneAssignment(order.getId(), order.getPickupStationId(),
                order.getDropoffStationId(), now));
        a.applyPlan(plan.drone().droneId(), plan.drone().stationId(), plan.chargerId(), plan.repositionKm(),
                plan.flightKm(), plan.consumptionPercent(), plan.batteryAtDeparture(), plan.departureAt(),
                plan.arrivalAt(), plan.waitMinutes(), plan.waitReason(), now);
        return Optional.of(assignments.save(a));
    }

    /** null nếu trạm chưa có tọa độ hợp lệ (không tính được khoảng cách). exceptAssignment: kế hoạch của chính đơn này không chiếm chỗ. */
    private Plan computePlan(Order order, Instant now, UUID exceptAssignment) {
        var byId = stations.findAllById(java.util.Set.of(order.getPickupStationId(), order.getDropoffStationId())).stream()
                .collect(Collectors.toMap(Station::getId, Function.identity()));
        Station pickup = byId.get(order.getPickupStationId());
        Station dropoff = byId.get(order.getDropoffStationId());
        if (pickup == null || dropoff == null || !GeoDistance.isValid(pickup.getLatitude(), pickup.getLongitude())
                || !GeoDistance.isValid(dropoff.getLatitude(), dropoff.getLongitude())) {
            return null;
        }
        var active = assignments.findByStatusIn(ACTIVE).stream()
                .filter(a -> exceptAssignment == null || !a.getId().equals(exceptAssignment)).toList();
        Set<UUID> busyDrones = active.stream().map(DroneAssignment::getDroneId).collect(Collectors.toSet());
        Set<UUID> reservedChargers = active.stream().map(DroneAssignment::getReservedChargerId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());

        var geo = stations.findAll().stream().filter(s -> GeoDistance.isValid(s.getLatitude(), s.getLongitude()))
                .collect(Collectors.toMap(Station::getId, s -> new Geo(s.getLatitude(), s.getLongitude())));
        var candidates = new ArrayList<DroneCandidate>();
        for (Drone d : drones.findAll()) {
            if (busyDrones.contains(d.getId()) || d.getStationId() == null || geo.get(d.getStationId()) == null) continue;
            if (d.getStatus() != DroneStatus.IDLE && d.getStatus() != DroneStatus.CHARGING) continue;
            boolean onCharger = d.getStatus() == DroneStatus.CHARGING;
            candidates.add(new DroneCandidate(d.getId(), d.getCode(), d.getStationId(), geo.get(d.getStationId()),
                    currentBattery(d, now), onCharger));
        }
        var destination = new ArrayList<DestinationDevice>();
        for (ChargingDevice c : devices.findByStationIdOrderByCode(order.getDropoffStationId())) {
            destination.add(new DestinationDevice(c.getId(), c.getCode(), c.isMaintenance(),
                    reservedChargers.contains(c.getId()), freeAt(c, now)));
        }
        return DispatchPlanner.plan(order.getPickupStationId(), geo.get(order.getPickupStationId()),
                geo.get(order.getDropoffStationId()), candidates, destination, now, eta.speedKmh());
    }

    /** Lúc dự kiến thiết bị nhận được drone khác (đã gồm khoảng đệm). Trống: ngay bây giờ. */
    private static Instant freeAt(ChargingDevice d, Instant now) {
        Instant margin = now.plus(Duration.ofMinutes(DispatchPlanner.RELEASE_MARGIN_MINUTES));
        return switch (ChargingRules.state(d, now)) {
            case EMPTY -> now;
            case FULL -> margin;
            case CHARGING -> {
                Instant ready = ChargingRules.readyAt(d);
                Instant after = ready == null ? margin : ready.plus(Duration.ofMinutes(DispatchPlanner.RELEASE_MARGIN_MINUTES));
                yield after.isAfter(margin) ? after : margin;
            }
            case MAINTENANCE -> margin;
        };
    }

    private static boolean isFree(ChargingDevice d, Instant now) {
        return ChargingRules.state(d, now) == ChargerState.EMPTY;
    }

    private int currentBattery(Drone d, Instant now) {
        if (d.getStatus() == DroneStatus.CHARGING) {
            var onDevice = devices.findByDroneId(d.getId()).map(c -> ChargingRules.currentPercent(c, now));
            if (onDevice.isPresent() && onDevice.get() != null) return onDevice.get();
        }
        return d.getBatteryPercent();
    }

    private Map<UUID, String> stationNames(List<Order> list) {
        var ids = new java.util.HashSet<UUID>();
        list.forEach(o -> { ids.add(o.getPickupStationId()); ids.add(o.getDropoffStationId()); });
        // Tên trạm xuất phát của drone cũng cần: lấy mọi trạm (số lượng nhỏ).
        return stations.findAll().stream().collect(Collectors.toMap(Station::getId, Station::getName,
                (x, y) -> x, HashMap::new));
    }

    private DispatchView viewOf(Order order, boolean staff, Instant now, Map<UUID, String> names) {
        String status = order.getStatus().name();
        var existing = assignments.findFirstByOrderIdAndStatusIn(order.getId(),
                EnumSet.of(AssignmentStatus.ASSIGNED, AssignmentStatus.IN_FLIGHT, AssignmentStatus.COMPLETED));
        if (existing.isPresent()) {
            var a = existing.get();
            String drone = staff ? drones.findById(a.getDroneId()).map(Drone::getCode).orElse(null) : null;
            String charger = staff && a.getReservedChargerId() != null
                    ? devices.findById(a.getReservedChargerId()).map(ChargingDevice::getCode).orElse(null) : null;
            boolean flying = a.getStatus() != AssignmentStatus.ASSIGNED;
            return new DispatchView(order.getId(), status, a.getStatus().name(), true, flying ? 0L : a.getWaitMinutes(),
                    flying ? DispatchPlanner.WAIT_NONE : a.getWaitReason(),
                    flying ? (a.getStatus() == AssignmentStatus.COMPLETED ? "Drone đã đến trạm nhận." : "Drone đang bay tới trạm nhận.")
                            : message(a.getWaitReason()),
                    a.getPlannedDepartureAt(), a.getPlannedArrivalAt(), drone,
                    staff ? names.get(a.getOriginStationId()) : null, staff ? a.getRepositionKm() : null, charger,
                    SOURCE, now);
        }
        if (!PLANNABLE.contains(order.getStatus())) {
            return new DispatchView(order.getId(), status, "NONE", false, null, null, null, null, null, null, null,
                    null, null, SOURCE, now);
        }
        // Chưa phân công: xem trước để biết dự kiến (không lưu gì).
        Plan plan = drones.count() == 0 ? null : computePlan(order, now, null);
        if (plan == null) {
            return new DispatchView(order.getId(), status, "NONE", false, null, null,
                    drones.count() == 0 ? "Hệ thống chưa có drone." : "Trạm chưa có tọa độ để tính điều phối.",
                    null, null, null, null, null, null, SOURCE, now);
        }
        if (!plan.assignable()) {
            String key = plan.outcome() == Outcome.NO_CHARGER ? "NO_CHARGER" : "NO_DRONE";
            return new DispatchView(order.getId(), status, key, false, null, key, message(key), null, null, null, null,
                    null, null, SOURCE, now);
        }
        return new DispatchView(order.getId(), status, "PLANNED", false, plan.waitMinutes(), plan.waitReason(),
                message(plan.waitReason()), plan.departureAt(), plan.arrivalAt(),
                staff ? plan.drone().code() : null,
                staff ? names.get(plan.drone().stationId()) : null, staff ? plan.repositionKm() : null,
                staff ? plan.chargerCode() : null, SOURCE, now);
    }

    static String message(String reason) {
        return switch (reason == null ? "" : reason) {
            case DispatchPlanner.WAIT_NONE -> "Drone sẵn sàng, có thể khởi hành ngay.";
            case DispatchPlanner.WAIT_DRONE_CHARGING -> "Drone đang sạc để đủ pin cho chặng bay.";
            case DispatchPlanner.WAIT_DRONE_REPOSITION -> "Drone từ trạm lân cận đang bay tới trạm gửi.";
            case DispatchPlanner.WAIT_DESTINATION_FULL ->
                    "Trạm nhận đang hết chỗ sạc; drone sẽ khởi hành để đến đúng lúc có chỗ.";
            case "NO_CHARGER" -> "Trạm nhận chưa có thiết bị sạc nào nhận được drone.";
            case "NO_DRONE" -> "Hiện chưa có drone đủ pin hoặc khả dụng.";
            default -> "";
        };
    }
}
