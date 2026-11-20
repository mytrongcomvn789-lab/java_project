package com.smartdrone.application.realtime;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Kênh thông báo: mỗi khách một kênh /topic/notifications/{userId}. Không có kênh chung, không ký tự đại diện. */
public final class NotificationChannel {
    public static final String PREFIX = "/topic/notifications/";
    private static final Pattern DESTINATION = Pattern.compile(
            "^/topic/notifications/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");

    private NotificationChannel() { }

    public static String destination(UUID userId) {
        return PREFIX + userId;
    }

    public static Optional<UUID> parse(String destination) {
        if (destination == null) return Optional.empty();
        Matcher m = DESTINATION.matcher(destination);
        return m.matches() ? Optional.of(UUID.fromString(m.group(1))) : Optional.empty();
    }
}