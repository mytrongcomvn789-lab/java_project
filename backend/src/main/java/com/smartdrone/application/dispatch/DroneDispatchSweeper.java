package com.smartdrone.application.dispatch;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Định kỳ gán drone cho đơn đang chờ và tính lại kế hoạch (bật bằng smartdrone.dispatch.auto-sweep=true, profile demo). */
@Component
@ConditionalOnProperty(name = "smartdrone.dispatch.auto-sweep", havingValue = "true")
public class DroneDispatchSweeper {
    private final DroneDispatchService service;

    public DroneDispatchSweeper(DroneDispatchService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${smartdrone.dispatch.sweep-interval-ms:30000}", initialDelay = 15000)
    public void run() {
        service.sweep();
    }
}
