package com.smartdrone.application.ai.summary;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.smartdrone.application.orders.dto.OrderDetail;
import com.smartdrone.application.orders.dto.StatusHistoryView;
import com.smartdrone.domain.enums.FailureReason;
import com.smartdrone.domain.enums.OrderStatus;
import com.smartdrone.domain.enums.RoleName;

/**
 * Dữ kiện XÁC ĐỊNH của một đơn, rút từ timeline trạng thái do Java tính. Đây là nguồn duy nhất cho prompt, cho
 * kiểm chứng đầu ra và cho bản tóm tắt dự phòng. Cố ý bỏ ghi chú tự do (reason), tên/SĐT/địa chỉ người nhận:
 * chỉ giữ mã lý do cố định, trạng thái, vai trò thực hiện và mốc thời gian.
 */
public record OrderSummaryFacts(UUID orderId, OrderStatus status, Instant createdAt, Instant updatedAt, Instant now,
                                List<Step> steps) {
    /** Múi giờ hiển thị cho người dùng. Mốc trong prompt cũng dùng múi này để văn bản AI khớp với timeline và bản dự phòng. */
    static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZONE);

    private static String fmt(Instant t) {
        return t == null ? "không rõ" : TIME.format(t);
    }

    /** Quy đổi số phút sang dạng dễ đọc: dưới 60 phút giữ phút; từ 60 phút là giờ; từ 24 giờ là ngày. */
    public static String duration(long totalMinutes) {
        long min = Math.max(0, totalMinutes);
        if (min < 60) return min + " phút";
        long hours = min / 60, m = min % 60;
        if (hours < 24) return m == 0 ? hours + " giờ" : hours + " giờ " + m + " phút";
        long days = hours / 24, h = hours % 24;
        return h == 0 ? days + " ngày" : days + " ngày " + h + " giờ";
    }

    /** Một bước của timeline; minutesSincePrevious = null ở bước đầu. */
    public record Step(OrderStatus status, Instant at, Long minutesSincePrevious, RoleName actorRole,
                       FailureReason reasonCode) { }

    public static OrderSummaryFacts from(OrderDetail order, Instant now) {
        List<StatusHistoryView> sorted = new ArrayList<>(order.history() == null ? List.of() : order.history());
        sorted.sort(Comparator.comparing(StatusHistoryView::occurredAt).thenComparingLong(StatusHistoryView::orderVersion));
        List<Step> steps = new ArrayList<>();
        Instant previous = null;
        for (StatusHistoryView h : sorted) {
            Long gap = previous == null ? null : Math.max(0, Duration.between(previous, h.occurredAt()).toMinutes());
            steps.add(new Step(h.toStatus(), h.occurredAt(), gap, h.actorRole(), h.reasonCode()));
            previous = h.occurredAt();
        }
        return new OrderSummaryFacts(order.id(), order.status(), order.createdAt(), order.updatedAt(), now,
                List.copyOf(steps));
    }

    /** Tổng số phút từ bước đầu đến bước cuối; null nếu dưới 2 bước. */
    public Long totalMinutes() {
        if (steps.size() < 2) return null;
        return Math.max(0, Duration.between(steps.get(0).at(), steps.get(steps.size() - 1).at()).toMinutes());
    }

    /** Khối văn bản đưa vào prompt (nhãn order_timeline). */
    public String toPromptText() {
        StringBuilder sb = new StringBuilder();
        sb.append("status: ").append(status).append(" (").append(status.label()).append(")\n");
        // Giờ Việt Nam (UTC+7), không phải UTC: nếu đưa Instant ISO thì mô hình sẽ viết giờ UTC, lệch 7 tiếng so với timeline.
        sb.append("timezone: Asia/Ho_Chi_Minh (UTC+7); mọi mốc thời gian dưới đây theo múi giờ này\n");
        sb.append("createdAt: ").append(fmt(createdAt)).append('\n');
        sb.append("updatedAt: ").append(fmt(updatedAt)).append('\n');
        sb.append("now: ").append(fmt(now)).append('\n');
        sb.append("stepCount: ").append(steps.size()).append('\n');
        if (totalMinutes() != null) sb.append("totalDuration: ").append(duration(totalMinutes())).append('\n');
        sb.append("timeline:\n");
        int i = 1;
        for (Step s : steps) {
            sb.append(i++).append(". ").append(fmt(s.at())).append(" | ").append(s.status()).append(" (")
                    .append(s.status().label()).append(')');
            if (s.minutesSincePrevious() != null) sb.append(" | sincePreviousStep=").append(duration(s.minutesSincePrevious()));
            if (s.actorRole() != null) sb.append(" | actor=").append(s.actorRole());
            if (s.reasonCode() != null) sb.append(" | reason=").append(s.reasonCode().label());
            sb.append('\n');
        }
        return sb.toString();
    }
}
