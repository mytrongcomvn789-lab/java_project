package com.smartdrone.application.realtime;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Quy ước tên kênh: mỗi đơn một kênh. Không có kênh chung và không cho đăng ký ký tự đại diện. */
public final class OrderChannel {
    public static final String PREFIX = "/topic/orders/";
    private static final Pattern DESTINATION = Pattern.compile(
            "^/topic/orders/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");

    private OrderChannel() { }

    public static String destination(UUID orderId) {
        return PREFIX + orderId;
    }

    /** Trả orderId nếu đích đúng dạng /topic/orders/{uuid}; mọi đích khác (kể cả /topic/**) là rỗng. */
    public static Optional<UUID> parse(String destination) {
        if (destination == null) return Optional.empty();
        Matcher m = DESTINATION.matcher(destination);
        return m.matches() ? Optional.of(UUID.fromString(m.group(1))) : Optional.empty();
    }
}