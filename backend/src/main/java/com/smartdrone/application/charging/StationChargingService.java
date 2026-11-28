package com.smartdrone.application.charging;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.admin.AuditService;
import com.smartdrone.application.charging.dto.ChargerIcon;
import com.smartdrone.application.charging.dto.ChargerSummary;
import com.smartdrone.application.charging.dto.ChargerView;
import com.smartdrone.application.charging.dto.DroneRef;
import com.smartdrone.application.charging.dto.IdleDroneView;
import com.smartdrone.application.charging.dto.StationChargersView;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.stations.StaffStationScope;
import com.smartdrone.application.stations.dto.StationConnection;
import com.smartdrone.domain.entity.ChargingDevice;
import com.smartdrone.domain.entity.Drone;
import com.smartdrone.domain.entity.Station;
import com.smartdrone.domain.enums.ChargerState;
import com.smartdrone.domain.enums.DroneStatus;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.ChargingDeviceRepository;
import com.smartdrone.infrastructure.persistence.DroneRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;

/**
 * Thiết bị sạc và drone của trạm. Chi tiết chỉ dành cho nhân viên trạm được phân công;
 * trang giám sát chung chỉ nhận biểu tượng (trạng thái + % pin) và tình trạng kết nối.
 */
@Service
public class StationChargingService {
    public static final String SOURCE_SIMULATED = "SIMULATED";
    public static final String SOURCE_HEARTBEAT = "CHARGER_HEARTBEAT";

    private final ChargingDeviceRepository devices;
    private final DroneRepository drones;
    private final StationRepository stations;
    private final StaffStationScope scope;
    private final AuditService audit;
    private final Clock clock;

    public StationChargingService(ChargingDeviceRepository devices, DroneRepository drones,
            StationRepository stations, StaffStationScope scope, AuditService audit, Clock clock) {
        this.devices = devices;
        this.drones = drones;
        this.stations = stations;
        this.scope = scope;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public StationChargersView detail(UUID actorId, UUID stationId) {
        return build(requireOwnStation(actorId, stationId));
    }

    /** Cho drone đã sạc đầy rời thiết bị: thiết bị trở về trống, drone chuyển sang đỗ tại trạm. */
    @Transactional
    public StationChargersView release(UUID actorId, UUID stationId, UUID chargerId) {
        Station station = requireOwnStation(actorId, stationId);
        Instant now = Instant.now(clock);
        var device = requireDevice(stationId, chargerId);
        if (ChargingRules.state(device, now) != ChargerState.FULL) {
            throw new ApiException(ErrorCode.REQUEST_ERROR,
                    "Chỉ cho drone rời khi pin đã đạt mức yêu cầu (thiết bị ở trạng thái đầy)");
        }
        var drone = drones.findById(device.getDroneId()).orElseThrow();
        int percent = ChargingRules.currentPercent(device, now);
        drone.park(percent);
        device.unplug();
        audit.record(actorId, "CHARGER_DRONE_RELEASED", "CHARGER", device.getId().toString(),
                "Thiết bị " + device.getCode() + ": drone " + drone.getCode() + " rời thiết bị sạc (pin " + percent + "%)");
        devices.flush();
        return build(station);
    }

    /** Bật/tắt bảo trì cho một thiết bị. Không cho bảo trì khi còn drone cắm trên thiết bị. */
    @Transactional
    public StationChargersView setMaintenance(UUID actorId, UUID stationId, UUID chargerId, boolean maintenance) {
        Station station = requireOwnStation(actorId, stationId);
        var device = requireDevice(stationId, chargerId);
        if (device.isMaintenance() != maintenance) {
            if (maintenance && device.getDroneId() != null) {
                throw new ApiException(ErrorCode.REQUEST_ERROR,
                        "Thiết bị đang có drone. Hãy cho drone rời thiết bị trước khi chuyển sang bảo trì");
            }
            device.setMaintenance(maintenance);
            audit.record(actorId, maintenance ? "CHARGER_MAINTENANCE_ON" : "CHARGER_MAINTENANCE_OFF", "CHARGER",
                    device.getId().toString(), "Thiết bị " + device.getCode()
                            + (maintenance ? " chuyển sang bảo trì" : " hoạt động trở lại"));
            devices.flush();
        }
        return build(station);
    }

    /** Biểu tượng + kết nối cho trang giám sát: một lần truy vấn cho mọi trạm. */
    @Transactional(readOnly = true)
    public Map<UUID, StationChargingOverview> overview(List<UUID> stationIds) {
        if (stationIds.isEmpty()) return Map.of();
        Instant now = Instant.now(clock);
        var all = devices.findByStationIdInOrderByCode(stationIds);
        return all.stream().collect(Collectors.groupingBy(ChargingDevice::getStationId)).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> {
                    var icons = e.getValue().stream().map(d -> new ChargerIcon(d.getCode(),
                            ChargingRules.state(d, now),
                            d.isMaintenance() ? null : ChargingRules.currentPercent(d, now))).toList();
                    return new StationChargingOverview(icons, connection(e.getValue(), now));
                }));
    }

    public record StationChargingOverview(List<ChargerIcon> chargers, StationConnection connection) { }

    /** Kết nối trạm = có ít nhất một thiết bị sạc gửi heartbeat trong khoảng cho phép. */
    static StationConnection connection(List<ChargingDevice> list, Instant now) {
        if (list.isEmpty()) return StationConnection.noData();
        Instant last = list.stream().map(ChargingDevice::getLastHeartbeatAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        boolean anyOnline = list.stream().anyMatch(d -> ChargingRules.online(d, now));
        return new StationConnection(anyOnline ? "ONLINE" : "OFFLINE", last, SOURCE_HEARTBEAT);
    }

    private StationChargersView build(Station station) {
        Instant now = Instant.now(clock);
        var list = devices.findByStationIdOrderByCode(station.getId());
        Map<UUID, Drone> byId = loadDrones(list);
        var views = list.stream().map(d -> view(d, byId.get(d.getDroneId()), now)).toList();
        var idle = drones.findByStationIdAndStatusOrderByCode(station.getId(), DroneStatus.IDLE).stream()
                .map(d -> new IdleDroneView(d.getId(), d.getCode(), d.getBatteryPercent())).toList();
        return new StationChargersView(station.getId(), station.getName(), station.getStatus(), summary(list, now),
                views, idle, nextSlotAt(list, now), SOURCE_SIMULATED, now);
    }

    private ChargerView view(ChargingDevice d, Drone drone, Instant now) {
        var state = ChargingRules.state(d, now);
        boolean hasDrone = d.getDroneId() != null;
        return new ChargerView(d.getId(), d.getCode(), state, ChargingRules.online(d, now), d.getLastHeartbeatAt(),
                hasDrone && drone != null ? new DroneRef(drone.getId(), drone.getCode()) : null,
                ChargingRules.currentPercent(d, now), d.getStartPercent(), hasDrone ? d.getTargetPercent() : null,
                ChargingRules.chargedMinutes(d, now), ChargingRules.remainingMinutes(d, now),
                ChargingRules.readyAt(d), ChargingRules.temperatureC(d, now), state == ChargerState.FULL);
    }

    private static ChargerSummary summary(List<ChargingDevice> list, Instant now) {
        int empty = 0, charging = 0, full = 0, maint = 0, online = 0;
        for (var d : list) {
            switch (ChargingRules.state(d, now)) {
                case EMPTY -> empty++;
                case CHARGING -> charging++;
                case FULL -> full++;
                case MAINTENANCE -> maint++;
            }
            if (ChargingRules.online(d, now)) online++;
        }
        return new ChargerSummary(list.size(), empty, charging, full, maint, online);
    }

    /**
     * Lúc dự kiến có chỗ nhận drone tiếp theo: null nếu đã có thiết bị trống.
     * Thiết bị đầy vẫn chờ drone rời nên chỉ tính từ thời điểm hiện tại.
     */
    private static Instant nextSlotAt(List<ChargingDevice> list, Instant now) {
        var times = new ArrayList<Instant>();
        for (var d : list) {
            switch (ChargingRules.state(d, now)) {
                case EMPTY -> { return null; }
                case FULL -> times.add(now);
                case CHARGING -> times.add(ChargingRules.readyAt(d));
                case MAINTENANCE -> { }
            }
        }
        return times.stream().min(Comparator.naturalOrder()).orElse(null);
    }

    private Map<UUID, Drone> loadDrones(List<ChargingDevice> list) {
        var ids = list.stream().map(ChargingDevice::getDroneId).filter(Objects::nonNull).toList();
        return ids.isEmpty() ? Map.of()
                : drones.findByIdIn(ids).stream().collect(Collectors.toMap(Drone::getId, Function.identity()));
    }

    private ChargingDevice requireDevice(UUID stationId, UUID chargerId) {
        return devices.findById(chargerId).filter(d -> d.getStationId().equals(stationId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }

    /** Trạm không tồn tại hoặc không được phân công đều trả 404 để không lộ trạm khác. */
    private Station requireOwnStation(UUID actorId, UUID stationId) {
        if (!scope.stationIdsFor(actorId, RoleName.STATION_OPERATOR).contains(stationId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return stations.findById(stationId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
