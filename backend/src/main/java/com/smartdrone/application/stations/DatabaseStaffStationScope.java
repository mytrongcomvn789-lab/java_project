package com.smartdrone.application.stations;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.StaffStationAssignmentRepository;

@Component
public class DatabaseStaffStationScope implements StaffStationScope {
    private final StaffStationAssignmentRepository assignments;
    public DatabaseStaffStationScope(StaffStationAssignmentRepository assignments) {
        this.assignments = assignments;
    }
    @Override
    @Transactional(readOnly = true)
    public Set<UUID> stationIdsFor(UUID staffId, RoleName role) {
        return switch (role) {
            case DISPATCHER, STATION_OPERATOR, LOGISTICS_MANAGER -> assignments.findStationIds(staffId, role);
            default -> Set.of();
        };
    }
}
