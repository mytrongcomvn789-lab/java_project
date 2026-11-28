package com.smartdrone.application.charging;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.smartdrone.application.stations.dto.StationConnection;
import com.smartdrone.domain.entity.ChargingDevice;
import com.smartdrone.domain.enums.ChargerState;

class ChargingRulesTest {
    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    private static ChargingDevice charging(int start, int minutesAgo) {
        var d = new ChargingDevice(UUID.randomUUID(), "A-01");
        d.plug(UUID.randomUUID(), start, NOW.minus(Duration.ofMinutes(minutesAgo)), 100);
        return d;
    }

    @Test
    void emptyDeviceHasNoBatteryData() {
        var d = new ChargingDevice(UUID.randomUUID(), "A-01");
        assertEquals(ChargerState.EMPTY, ChargingRules.state(d, NOW));
        assertNull(ChargingRules.currentPercent(d, NOW));
        assertNull(ChargingRules.remainingMinutes(d, NOW));
        assertNull(ChargingRules.readyAt(d));
    }

    @Test
    void percentGrowsLinearlyAndStopsAtTarget() {
        var d = charging(20, 30);
        assertEquals(50, ChargingRules.currentPercent(d, NOW));
        assertEquals(ChargerState.CHARGING, ChargingRules.state(d, NOW));
        assertEquals(50L, ChargingRules.remainingMinutes(d, NOW));
        assertEquals(30L, ChargingRules.chargedMinutes(d, NOW));
        assertEquals(NOW.plus(Duration.ofMinutes(50)), ChargingRules.readyAt(d));

        var done = charging(80, 60);
        assertEquals(100, ChargingRules.currentPercent(done, NOW));
        assertEquals(ChargerState.FULL, ChargingRules.state(done, NOW));
        assertEquals(0L, ChargingRules.remainingMinutes(done, NOW));
    }

    @Test
    void maintenanceOverridesEverything() {
        var d = new ChargingDevice(UUID.randomUUID(), "A-08");
        d.setMaintenance(true);
        assertEquals(ChargerState.MAINTENANCE, ChargingRules.state(d, NOW));
    }

    @Test
    void onlineDependsOnRecentHeartbeat() {
        var d = new ChargingDevice(UUID.randomUUID(), "A-01");
        assertFalse(ChargingRules.online(d, NOW));
        d.heartbeat(NOW.minusSeconds(60));
        assertTrue(ChargingRules.online(d, NOW));
        d.heartbeat(NOW.minus(Duration.ofMinutes(5)));
        assertFalse(ChargingRules.online(d, NOW));
    }

    @Test
    void stationConnectionComesFromHeartbeats() {
        assertEquals("UNKNOWN", StationChargingService.connection(List.of(), NOW).state());

        var online = new ChargingDevice(UUID.randomUUID(), "A-01");
        online.heartbeat(NOW.minusSeconds(10));
        var stale = new ChargingDevice(UUID.randomUUID(), "A-02");
        StationConnection c = StationChargingService.connection(List.of(online, stale), NOW);
        assertEquals("ONLINE", c.state());
        assertEquals(StationChargingService.SOURCE_HEARTBEAT, c.source());

        assertEquals("OFFLINE", StationChargingService.connection(List.of(stale), NOW).state());
    }
}
