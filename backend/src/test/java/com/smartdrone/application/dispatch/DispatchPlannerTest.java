package com.smartdrone.application.dispatch;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.smartdrone.application.dispatch.DispatchPlanner.DestinationDevice;
import com.smartdrone.application.dispatch.DispatchPlanner.DroneCandidate;
import com.smartdrone.application.dispatch.DispatchPlanner.Geo;
import com.smartdrone.application.dispatch.DispatchPlanner.Outcome;

class DispatchPlannerTest {
    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");
    private static final double SPEED = 40;

    // A -> B khoảng 10 km; C nằm gần A (khoảng 3 km); D xa hơn.
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID(), D = UUID.randomUUID();
    private static final Geo GA = new Geo(10.7769, 106.7009), GB = new Geo(10.8505, 106.7717),
            GC = new Geo(10.7626, 106.6602), GD = new Geo(10.9500, 106.8500);

    private static DroneCandidate drone(String code, UUID st, Geo at, int battery, boolean onCharger) {
        return new DroneCandidate(UUID.randomUUID(), code, st, at, battery, onCharger);
    }

    private static DestinationDevice freeDevice(String code) {
        return new DestinationDevice(UUID.randomUUID(), code, false, false, NOW);
    }

    private static DispatchPlanner.Plan plan(List<DroneCandidate> drones, List<DestinationDevice> devices) {
        return DispatchPlanner.plan(A, GA, GB, drones, devices, NOW, SPEED);
    }

    @Test
    void droneAtPickupStationDepartsImmediatelyAndReservesEmptyDevice() {
        var p = plan(List.of(drone("DR-1", A, GA, 90, false)), List.of(freeDevice("B-01")));
        assertEquals(Outcome.READY, p.outcome());
        assertEquals(0, p.waitMinutes());
        assertEquals(DispatchPlanner.WAIT_NONE, p.waitReason());
        assertEquals(0.0, p.repositionKm());
        assertEquals("B-01", p.chargerCode());
        assertTrue(p.arrivalAt().isAfter(p.departureAt()));
    }

    @Test
    void prefersDroneAtPickupOverNearbyStation() {
        var local = drone("DR-LOCAL", A, GA, 80, false);
        var near = drone("DR-NEAR", C, GC, 100, false);
        var p = plan(List.of(near, local), List.of(freeDevice("B-01")));
        assertEquals("DR-LOCAL", p.drone().code());
    }

    @Test
    void withoutLocalDronePicksNearestStationAndWaitsForItToFly() {
        var far = drone("DR-FAR", D, GD, 100, false);
        var near = drone("DR-NEAR", C, GC, 100, false);
        var p = plan(List.of(far, near), List.of(freeDevice("B-01")));
        assertEquals("DR-NEAR", p.drone().code());
        assertTrue(p.repositionKm() > 0);
        assertTrue(p.waitMinutes() > 0);
        assertEquals(DispatchPlanner.WAIT_DRONE_REPOSITION, p.waitReason());
        assertEquals(Outcome.WAITING, p.outcome());
    }

    @Test
    void lowBatteryOnChargerWaitsForChargeButIdleLowBatteryIsSkipped() {
        var charging = drone("DR-CHG", A, GA, 20, true);
        var p = plan(List.of(charging), List.of(freeDevice("B-01")));
        assertEquals(Outcome.WAITING, p.outcome());
        assertEquals(DispatchPlanner.WAIT_DRONE_CHARGING, p.waitReason());
        assertTrue(p.waitMinutes() > 0);

        var idleLow = drone("DR-LOW", A, GA, 20, false);
        assertEquals(Outcome.NO_DRONE, plan(List.of(idleLow), List.of(freeDevice("B-01"))).outcome());
    }

    @Test
    void fullDestinationDoesNotBlockDepartureUntilDeviceIsFree() {
        var busy = new DestinationDevice(UUID.randomUUID(), "B-01", false, false, NOW.plus(Duration.ofMinutes(60)));
        var p = plan(List.of(drone("DR-1", A, GA, 90, false)), List.of(busy));
        assertEquals(DispatchPlanner.WAIT_DESTINATION_FULL, p.waitReason());
        // Đến đúng lúc thiết bị được giải phóng, không đợi thiết bị trống mới xuất phát.
        assertEquals(busy.freeAt(), p.arrivalAt());
        assertTrue(p.departureAt().isBefore(p.arrivalAt()));
        assertTrue(p.waitMinutes() > 0 && p.waitMinutes() < 60);
    }

    @Test
    void earliestFreeDeviceIsChosenAndReservedOrMaintenanceOnesAreSkipped() {
        var maintenance = new DestinationDevice(UUID.randomUUID(), "B-01", true, false, NOW);
        var reserved = new DestinationDevice(UUID.randomUUID(), "B-02", false, true, NOW);
        var later = new DestinationDevice(UUID.randomUUID(), "B-03", false, false, NOW.plus(Duration.ofMinutes(5)));
        var p = plan(List.of(drone("DR-1", A, GA, 90, false)), List.of(maintenance, reserved, later));
        assertEquals("B-03", p.chargerCode());

        var none = plan(List.of(drone("DR-1", A, GA, 90, false)), List.of(maintenance, reserved));
        assertEquals(Outcome.NO_CHARGER, none.outcome());
        assertFalse(none.assignable());
    }

    @Test
    void noDroneAtAllMeansNoDrone() {
        assertEquals(Outcome.NO_DRONE, plan(List.of(), List.of(freeDevice("B-01"))).outcome());
    }
}
