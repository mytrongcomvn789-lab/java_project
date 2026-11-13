package com.smartdrone.application.stations;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * TẠM THỜI, chỉ để 3.6 chạy được khi chưa có bảng trạm. Coi mọi trạm là hợp lệ.
 * Khi có bản thật (một class khác implements StationDirectory), app sẽ báo lỗi "2 bean", lúc đó XÓA file này.
 */
@Component
public class TemporaryStationDirectory implements StationDirectory {

    private static final Logger log = LoggerFactory.getLogger(TemporaryStationDirectory.class);

    @Override
    public boolean isActive(UUID stationId) {
        log.warn("TẠM THỜI: chưa có bảng trạm, coi trạm {} là hợp lệ", stationId);
        return true;
    }
}