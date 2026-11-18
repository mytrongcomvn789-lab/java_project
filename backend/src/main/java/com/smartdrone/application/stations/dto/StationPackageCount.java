package com.smartdrone.application.stations.dto;

import java.util.UUID;

/** Một dòng kết quả của truy vấn đếm: trạm này có bao nhiêu kiện. */
public record StationPackageCount(UUID stationId, long packages) { }