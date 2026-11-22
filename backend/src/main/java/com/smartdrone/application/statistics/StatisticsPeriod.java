package com.smartdrone.application.statistics;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;

/**
 * Bộ lọc đã chuẩn hóa: ngày địa phương [from, to] (gồm cả hai đầu) đổi thành khoảng Instant nửa mở
 * [start, end). Dashboard (5.2) và báo cáo (5.3) cùng đi qua lớp này nên không lệch múi giờ/biên ngày.
 */
public record StatisticsPeriod(LocalDate from, LocalDate to, Instant start, Instant end,
                               DateBasis basis, UUID stationId) {

    /** Múi giờ cố định của hệ thống, không nhận từ client. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final int MAX_DAYS = 366;
    public static final int DEFAULT_DAYS = 30;

    /** from/to/basis có thể null: mặc định 30 ngày gần nhất theo ngày tạo đơn. */
    public static StatisticsPeriod of(LocalDate from, LocalDate to, DateBasis basis, UUID stationId, Clock clock) {
        LocalDate toDay = to != null ? to : LocalDate.now(clock.withZone(ZONE));
        LocalDate fromDay = from != null ? from : toDay.minusDays(DEFAULT_DAYS - 1L);
        if (fromDay.isAfter(toDay)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "from không được sau to");
        }
        if (fromDay.plusDays(MAX_DAYS).isBefore(toDay.plusDays(1))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Khoảng thời gian tối đa " + MAX_DAYS + " ngày");
        }
        Instant start = fromDay.atStartOfDay(ZONE).toInstant();
        Instant end = toDay.plusDays(1).atStartOfDay(ZONE).toInstant(); // loại trừ
        return new StatisticsPeriod(fromDay, toDay, start, end,
                basis != null ? basis : DateBasis.CREATED, stationId);
    }

    public int days() {
        return (int) (to.toEpochDay() - from.toEpochDay() + 1);
    }
}
