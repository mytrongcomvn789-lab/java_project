package com.smartdrone.application.ai.summary;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.smartdrone.application.ai.summary.OrderSummaryVerifier.Summary;
import com.smartdrone.domain.enums.OrderStatus;

/** Tóm tắt xác định (không AI) khi AI tắt, lỗi hoặc trả nội dung không qua kiểm chứng. Chỉ dùng dữ kiện của timeline. */
@Component
public class OrderSummaryFallback {
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    public Summary build(OrderSummaryFacts f) {
        StringBuilder sb = new StringBuilder("Đơn hàng hiện ở trạng thái \"").append(f.status().label()).append("\"");
        sb.append(", tạo lúc ").append(fmt(f.createdAt()));
        sb.append(" và cập nhật gần nhất lúc ").append(fmt(f.updatedAt())).append('.');
        if (f.steps().isEmpty()) sb.append(" Chưa có lịch sử xử lý để tóm tắt.");
        else sb.append(" Đơn đã qua ").append(f.steps().size()).append(" bước xử lý.");
        List<String> highlights = new ArrayList<>();
        if (!f.steps().isEmpty()) {
            var last = f.steps().get(f.steps().size() - 1);
            highlights.add("Bước gần nhất: " + last.status().label() + " lúc " + fmt(last.at()) + ".");
            if (f.totalMinutes() != null) {
                highlights.add("Từ bước đầu đến bước gần nhất: " + f.totalMinutes() + " phút.");
            }
            f.steps().stream().filter(s -> s.reasonCode() != null).reduce((a, b) -> b).ifPresent(s ->
                    highlights.add("Lý do ghi nhận: " + s.reasonCode().label() + "."));
        }
        if (f.status() == OrderStatus.FAILED) highlights.add("Giao thất bại, đơn chờ xử lý tiếp (giao lại hoặc hoàn hàng).");
        if (f.status() == OrderStatus.RETURNING) highlights.add("Hàng đang được hoàn về trạm gửi.");
        if (f.status() == OrderStatus.RETURNED) highlights.add("Hàng đã hoàn về trạm gửi.");
        return new Summary(sb.toString(), List.copyOf(highlights));
    }

    private static String fmt(Instant t) {
        return t == null ? "không rõ" : TIME.format(t);
    }
}
