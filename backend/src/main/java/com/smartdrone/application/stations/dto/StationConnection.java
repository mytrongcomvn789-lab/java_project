package com.smartdrone.application.stations.dto;

import java.time.Instant;

/** Tình trạng kết nối của trạm. Tách riêng khỏi trạng thái vận hành (hoạt động/bảo trì). */
public record StationConnection(String state, Instant lastSeenAt, String source) {

    /** Chưa có nguồn heartbeat nên không được giả online: báo "chưa có dữ liệu". */
    public static StationConnection noData() {
        return new StationConnection("UNKNOWN", null, "NONE");
    }
}