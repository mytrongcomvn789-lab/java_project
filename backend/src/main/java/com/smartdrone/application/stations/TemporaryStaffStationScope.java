package com.smartdrone.application.stations;

import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.smartdrone.domain.enums.RoleName;

/**
 * TẠM THỜI: chưa có bảng phân công trạm nên trả về tập rỗng = nhân viên KHÔNG thấy đơn nào (từ chối mặc định).
 * Khi có bản thật (class khác implements StaffStationScope) thì XÓA file này.
 */
@Component
public class TemporaryStaffStationScope implements StaffStationScope {
    private static final Logger log = LoggerFactory.getLogger(TemporaryStaffStationScope.class);

    @Override
    public Set<UUID> stationIdsFor(UUID staffId, RoleName role) {
        log.warn("TẠM THỜI: chưa có phân công trạm, {} {} không thấy đơn nào", role, staffId);
        return Set.of();
    }
}