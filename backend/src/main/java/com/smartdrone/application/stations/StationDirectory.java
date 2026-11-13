package com.smartdrone.application.stations;

import java.util.UUID;

/** Cổng hỏi "trạm này có tồn tại và đang hoạt động không". Người làm trạm cài đặt cổng này. */
public interface StationDirectory {
    boolean isActive(UUID stationId);
}