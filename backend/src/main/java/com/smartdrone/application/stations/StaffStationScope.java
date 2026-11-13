package com.smartdrone.application.stations;

import java.util.Set;
import java.util.UUID;
import com.smartdrone.domain.enums.RoleName;

/** Cổng hỏi "nhân viên này được xem đơn của những trạm nào". Người làm trạm/tổ chức cài đặt cổng này. */
public interface StaffStationScope {
    Set<UUID> stationIdsFor(UUID staffId, RoleName role);
}