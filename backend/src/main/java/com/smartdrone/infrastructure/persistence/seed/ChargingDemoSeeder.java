package com.smartdrone.infrastructure.persistence.seed;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.domain.entity.ChargingDevice;
import com.smartdrone.domain.entity.Drone;
import com.smartdrone.domain.enums.DroneStatus;
import com.smartdrone.infrastructure.persistence.ChargingDeviceRepository;
import com.smartdrone.infrastructure.persistence.DroneRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;

/**
 * Dữ liệu demo cho thiết bị sạc và drone: 4 trạm A-D, mỗi trạm 8 thiết bị sạc và 4 drone,
 * có đủ trạng thái trống / đang sạc / đầy / bảo trì để xem được trên giao diện.
 * Chỉ chạy cùng DemoDataSeeder (app.seed.demo=true) và chỉ khi chưa có thiết bị sạc nào.
 */
@Component
@Order(4)
@ConditionalOnProperty(name = "app.seed.demo", havingValue = "true")
public class ChargingDemoSeeder implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(ChargingDemoSeeder.class);
    private static final int DEVICES_PER_STATION = 8;

    /** Một drone: đang cắm sạc (pin ban đầu, số phút đã sạc) hoặc đang đỗ (pin hiện tại). */
    private record Seed(Integer startPercent, int minutesAgoOrBattery) {
        static Seed charging(int startPercent, int minutesAgo) { return new Seed(startPercent, minutesAgo); }
        static Seed idle(int battery) { return new Seed(null, battery); }
    }

    private record Layout(String letter, List<Seed> drones, List<Integer> maintenanceDevices) { }

    private static final List<Layout> LAYOUTS = List.of(
            new Layout("A", List.of(Seed.charging(20, 30), Seed.charging(55, 10), Seed.charging(80, 60),
                    Seed.idle(100)), List.of(8)),
            new Layout("B", List.of(Seed.charging(10, 15), Seed.charging(50, 70), Seed.charging(30, 55),
                    Seed.idle(96)), List.of()),
            new Layout("C", List.of(Seed.charging(5, 20), Seed.charging(40, 25), Seed.charging(15, 40),
                    Seed.idle(88)), List.of(7, 8)),
            new Layout("D", List.of(Seed.charging(60, 5), Seed.charging(70, 10), Seed.charging(0, 90),
                    Seed.charging(35, 45)), List.of()));

    /** Tên trạm theo chữ cái bố cục: tên hiện tại của DemoDataSeeder và tên cũ (DB tạo bằng bản trước khi bỏ chữ "Demo"). */
    private static final java.util.Map<String, List<String>> STATION_NAMES = java.util.Map.of(
            "A", List.of("Trạm Bến Thành", "Trạm Demo A"),
            "B", List.of("Trạm Thủ Đức", "Trạm Demo B"),
            "C", List.of("Trạm Chợ Lớn", "Trạm Demo C"),
            "D", List.of("Trạm Bình Thạnh", "Trạm Demo D"));

    private final StationRepository stations;
    private final ChargingDeviceRepository devices;
    private final DroneRepository drones;
    private final Clock clock;

    public ChargingDemoSeeder(StationRepository stations, ChargingDeviceRepository devices,
            DroneRepository drones, Clock clock) {
        this.stations = stations;
        this.devices = devices;
        this.drones = drones;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (devices.count() > 0 || drones.count() > 0) {
            log.info("Dữ liệu demo: thiết bị sạc và drone đã có, bỏ qua.");
            return;
        }
        Instant now = Instant.now(clock);
        for (Layout layout : LAYOUTS) {
            var station = stations.findAll().stream()
                    .filter(s -> STATION_NAMES.get(layout.letter()).contains(s.getName())).findFirst().orElse(null);
            if (station == null) continue;

            var created = new java.util.ArrayList<ChargingDevice>();
            for (int i = 1; i <= DEVICES_PER_STATION; i++) {
                var device = new ChargingDevice(station.getId(), "%s-%02d".formatted(layout.letter(), i));
                boolean maintenance = layout.maintenanceDevices().contains(i);
                device.setMaintenance(maintenance);
                if (!maintenance) device.heartbeat(now);
                created.add(device);
            }
            int next = 0;
            for (int n = 0; n < layout.drones().size(); n++) {
                var seed = layout.drones().get(n);
                String code = "DR-%s%d".formatted(layout.letter(), n + 1);
                if (seed.startPercent() == null) {
                    drones.save(new Drone(code, seed.minutesAgoOrBattery(), DroneStatus.IDLE, station.getId()));
                } else {
                    var drone = drones.save(new Drone(code, seed.startPercent(), DroneStatus.CHARGING, station.getId()));
                    while (created.get(next).isMaintenance()) next++;
                    created.get(next++).plug(drone.getId(), seed.startPercent(),
                            now.minusSeconds(seed.minutesAgoOrBattery() * 60L), 100);
                }
            }
            devices.saveAll(created);
        }
        log.info("Dữ liệu demo: đã tạo thiết bị sạc và drone (số liệu sạc là giả lập).");
    }
}
