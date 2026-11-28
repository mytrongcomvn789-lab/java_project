package com.smartdrone.application.charging;

import java.time.Clock;
import java.time.Instant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.infrastructure.persistence.ChargingDeviceRepository;

/**
 * Giả lập heartbeat của thiết bị sạc khi chưa có phần cứng thật (chỉ bật khi
 * smartdrone.charging.simulate-heartbeat=true, tức profile demo). Thiết bị đang bảo trì không gửi
 * heartbeat nên sẽ hiện mất kết nối.
 */
@Component
@ConditionalOnProperty(name = "smartdrone.charging.simulate-heartbeat", havingValue = "true")
public class ChargerHeartbeatSimulator {
    private final ChargingDeviceRepository devices;
    private final Clock clock;

    public ChargerHeartbeatSimulator(ChargingDeviceRepository devices, Clock clock) {
        this.devices = devices;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${smartdrone.charging.heartbeat-interval-ms:30000}")
    @Transactional
    public void beat() {
        Instant now = Instant.now(clock);
        for (var d : devices.findAll()) {
            if (!d.isMaintenance()) d.heartbeat(now);
        }
    }
}
