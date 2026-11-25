package com.smartdrone.application.ai.analytics;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import com.smartdrone.application.statistics.StatisticsFormulas;

/** Deterministic evidence, never model-generated measurements or causal claims. */
@Component
public class OperationsRules {
    public static final String VERSION = "ops-rules-v1";
    public static final int MIN_OUTCOMES = 20;
    public static final double FAILURE_THRESHOLD = 0.20;
    public static final double LOAD_THRESHOLD = 0.80;

    public record Evidence(String id, String text, String source, String recommendation) { }
    public record Trend(int daysPerWindow, LocalDate firstFrom, LocalDate firstTo,
            LocalDate lastFrom, LocalDate lastTo, LocalDate omittedMiddleDay,
            long firstOrders, long lastOrders, long delta, Double changeRate) { }
    public record Findings(Double failureRate, long failureSample, Trend trend,
            List<String> warnings, List<Evidence> evidence) { }

    public Findings evaluate(OperationsDataset data) {
        var s = data.summary();
        var c = s.orders();
        long sample = c.deliveryOutcomes();
        Double failure = StatisticsFormulas.ratio(c.failed(), sample);
        var warnings = new ArrayList<String>();
        warnings.add("CURRENT_STATUS_NOT_HISTORICAL_SNAPSHOT");
        warnings.add("STATION_LOAD_IS_CURRENT_SLOT_NOT_PERIOD_TOTAL");
        warnings.add("READ_COMMITTED_NOT_FROZEN_SNAPSHOT");
        if (c.total() == 0) warnings.add("NO_ORDERS_IN_SCOPE");
        if (sample < MIN_OUTCOMES) warnings.add("LOW_DELIVERY_SAMPLE");
        if (s.deliveryTime().count() == 0) warnings.add("NO_DELIVERY_DURATION_SAMPLE");
        if (s.deliveryTime().excludedOrders() > 0) warnings.add("MISSING_DELIVERY_TIMESTAMPS");
        if (data.stationLoad().isEmpty()) warnings.add("NO_VISIBLE_STATIONS");
        if (data.stationLoad().stream().anyMatch(x -> x.utilization() == null))
            warnings.add("UNDEFINED_STATION_UTILIZATION");

        var evidence = new ArrayList<Evidence>();
        evidence.add(new Evidence("ORDER_VOLUME", "Có " + c.total() + " đơn trong phạm vi đã chọn.",
                "summary.orders.total", "Đối chiếu danh sách đơn và bộ lọc trước khi ra quyết định."));
        if (sample > 0) evidence.add(new Evidence("DELIVERY_OUTCOMES",
                "Mẫu kết quả giao: " + sample + "; thành công " + percent(s.rates().successRate())
                + "; FAILED " + percent(failure) + ". RETURNING/RETURNED được tính riêng.",
                "summary.orders + summary.rates", "Rà soát các đơn FAILED và hoàn hàng; chưa kết luận nguyên nhân."));
        if (s.deliveryTime().count() > 0) evidence.add(new Evidence("DELIVERY_TIME",
                "Thời gian từ DISPATCH cuối đến CONFIRM trung bình "
                + String.format(Locale.ROOT, "%.2f", s.deliveryTime().avgSeconds())
                + " giây trên " + s.deliveryTime().count() + " đơn đủ mốc.",
                "summary.deliveryTime", "Kiểm tra các mốc giao trước khi so sánh thời gian giữa các nhóm."));
        if (s.deliveryTime().excludedOrders() > 0) evidence.add(new Evidence("MISSING_TIMESTAMPS",
                "Có " + s.deliveryTime().excludedOrders() + " đơn đã giao bị loại khỏi mẫu thời gian do thiếu mốc.",
                "summary.deliveryTime.excludedOrders", "Bổ sung hoặc kiểm tra lịch sử giao nhận còn thiếu."));

        Trend trend = trend(data);
        if (trend == null) warnings.add("TREND_REQUIRES_TWO_DAYS");
        else {
            if (trend.firstOrders() == 0) warnings.add("TREND_ZERO_BASELINE");
            evidence.add(new Evidence("ORDER_TREND", "Hai cửa sổ cùng " + trend.daysPerWindow()
                    + " ngày có " + trend.firstOrders() + " và " + trend.lastOrders()
                    + " đơn; chênh lệch " + trend.delta() + ". Không suy ra nguyên nhân hoặc dự báo.",
                    "daily.orders.total", "Kiểm tra các ngày và cơ cấu đơn trước khi điều chỉnh kế hoạch."));
        }
        if (!data.stationLoad().isEmpty()) {
            long booked = data.stationLoad().stream().mapToLong(x -> x.booked()).sum();
            evidence.add(new Evidence("STATION_LOAD", "Trong khung giờ hiện tại, "
                    + data.stationLoad().size() + " trạm được phép xem có tổng " + booked
                    + " lượt đặt chỗ tại trạm; không phải số đơn duy nhất.",
                    "stationLoad.booked", "Xem tải từng trạm và lịch hiện tại trước khi phân bổ lượt giao."));
        }
        if (sample >= MIN_OUTCOMES && failure != null && failure >= FAILURE_THRESHOLD)
            evidence.add(new Evidence("HIGH_FAILURE", "Tỷ lệ FAILED đạt ngưỡng rà soát "
                    + percent(FAILURE_THRESHOLD) + " với ít nhất " + MIN_OUTCOMES + " kết quả giao.",
                    "failureRate >= 0.20 AND failureSample >= 20",
                    "Rà soát đơn thất bại; ngưỡng chỉ là quy tắc cảnh báo, không chứng minh bất thường thống kê."));
        long loaded = data.stationLoad().stream()
                .filter(x -> x.utilization() != null && x.utilization() >= LOAD_THRESHOLD).count();
        if (loaded > 0) evidence.add(new Evidence("HIGH_LOAD", "Có " + loaded
                + " trạm có tỷ lệ đặt chỗ/sức chứa từ " + percent(LOAD_THRESHOLD) + " trong khung giờ hiện tại.",
                "stationLoad.utilization >= 0.80",
                "Kiểm tra tải trạm và lịch giao; báo cáo không tự thay đổi lịch hoặc sức chứa."));
        return new Findings(failure, sample, trend, List.copyOf(warnings), List.copyOf(evidence));
    }

    private static Trend trend(OperationsDataset data) {
        var daily = data.daily();
        int half = daily.size() / 2;
        if (half == 0) return null;
        long first = daily.subList(0, half).stream().mapToLong(x -> x.orders().total()).sum();
        long last = daily.subList(daily.size() - half, daily.size()).stream()
                .mapToLong(x -> x.orders().total()).sum();
        return new Trend(half, daily.getFirst().date(), daily.get(half - 1).date(),
                daily.get(daily.size() - half).date(), daily.getLast().date(),
                daily.size() % 2 == 0 ? null : daily.get(half).date(), first, last, last - first,
                StatisticsFormulas.ratio(last - first, first));
    }

    private static String percent(Double value) {
        return value == null ? "chưa có dữ liệu" : String.format(Locale.ROOT, "%.2f%%", value * 100);
    }
}
