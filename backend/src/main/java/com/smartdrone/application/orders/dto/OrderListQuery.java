package com.smartdrone.application.orders.dto;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.enums.OrderStatus;

public record OrderListQuery(UUID orderId, OrderStatus status, Instant from, Instant to,
                             UUID stationId, int page, int pageSize, Sort sort) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    private static final Duration MAX_RANGE = Duration.ofDays(366);
    private static final int MAX_TEXT = 64;
    private static final Set<String> SORT_FIELDS = Set.of("createdAt", "updatedAt", "status"); // danh sách cho phép

    public static OrderListQuery of(String code, String status, String from, String to, String stationId,
                                    int page, int pageSize, String sort) {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw bad("page phải từ 1; pageSize từ 1 đến " + MAX_PAGE_SIZE);
        }
        Instant f = instant("from", from);
        Instant t = instant("to", to);
        if (f != null && t != null) {
            if (!f.isBefore(t)) throw bad("from phải trước to");
            if (Duration.between(f, t).compareTo(MAX_RANGE) > 0) throw bad("Khoảng thời gian tối đa 366 ngày");
        }
        return new OrderListQuery(uuid("code", code), status(status), f, t,
                uuid("stationId", stationId), page, pageSize, sort(sort));
    }

    private static UUID uuid(String name, String v) {
        if (blank(v)) return null;
        try {
            return UUID.fromString(limit(name, v));
        } catch (IllegalArgumentException e) {
            throw bad(name + " phải là UUID");
        }
    }

    private static OrderStatus status(String v) {
        if (blank(v)) return null;
        try {
            return OrderStatus.valueOf(limit("status", v).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw bad("status không hợp lệ");
        }
    }

    private static Instant instant(String name, String v) {
        if (blank(v)) return null;
        try {
            return Instant.parse(limit(name, v)); // ISO-8601 UTC, ví dụ 2026-11-09T00:00:00Z
        } catch (DateTimeParseException e) {
            throw bad(name + " phải theo ISO-8601 UTC, ví dụ 2026-11-09T00:00:00Z");
        }
    }

    /** sort=createdAt,desc. Chỉ nhận trường trong danh sách cho phép; luôn thêm id để thứ tự ổn định. */
    private static Sort sort(String v) {
        String field = "createdAt";
        Sort.Direction dir = Sort.Direction.DESC;
        if (!blank(v)) {
            String[] p = limit("sort", v).split(",");
            if (p.length > 2 || !SORT_FIELDS.contains(p[0].trim())) {
                throw bad("sort chỉ hỗ trợ: " + SORT_FIELDS);
            }
            field = p[0].trim();
            dir = Sort.Direction.ASC;
            if (p.length == 2) {
                dir = switch (p[1].trim().toLowerCase(Locale.ROOT)) {
                    case "asc" -> Sort.Direction.ASC;
                    case "desc" -> Sort.Direction.DESC;
                    default -> throw bad("Hướng sắp xếp phải là asc hoặc desc");
                };
            }
        }
        return Sort.by(new Sort.Order(dir, field), Sort.Order.asc("id"));
    }

    private static String limit(String name, String v) {
        if (v.length() > MAX_TEXT) throw bad(name + " quá dài");
        return v.trim();
    }
    private static boolean blank(String v) { return v == null || v.isBlank(); }
    private static ApiException bad(String m) { return new ApiException(ErrorCode.VALIDATION_ERROR, m); }
}