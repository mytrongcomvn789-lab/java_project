package com.smartdrone.application.realtime;

import com.smartdrone.application.notifications.dto.NotificationView;

/** Phong bì gửi qua socket. Nội dung thông báo giống hệt một phần tử của GET /v1/notifications. */
public record NotificationRealtimeEvent(int schemaVersion, String type, NotificationView notification) {
    public static final int SCHEMA_VERSION = 1;
    public static final String TYPE_CREATED = "NOTIFICATION_CREATED";

    public static NotificationRealtimeEvent created(NotificationView view) {
        return new NotificationRealtimeEvent(SCHEMA_VERSION, TYPE_CREATED, view);
    }
}