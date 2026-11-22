package com.smartdrone.application.admin;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;

// Định nghĩa các cấu hình hệ thống: nhóm, kiểu, khoảng giá trị, mặc định.
// GHI CHÚ: giá trị được lưu ở bảng system_settings; các module nghiệp vụ sẽ đọc dần ở task sau.
public final class SettingDefinitions {
    private SettingDefinitions() { }

    public record Def(String key, String group, String label, String description, String type,
                      String defaultValue, Integer min, Integer max) {

        // Kiểm tra và chuẩn hóa giá trị; ném VALIDATION_ERROR kèm tên cấu hình nếu sai.
        public String normalize(String raw) {
            String v = raw == null ? "" : raw.trim();
            switch (type) {
                case "INT" -> {
                    int n;
                    try {
                        n = Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        throw invalid("phải là số nguyên");
                    }
                    if (n < min || n > max) {
                        throw invalid("phải từ " + min + " đến " + max);
                    }
                    return Integer.toString(n);
                }
                case "BOOLEAN" -> {
                    if (!v.equals("true") && !v.equals("false")) {
                        throw invalid("phải là true hoặc false");
                    }
                    return v;
                }
                default -> {
                    if (key.equals("system.timezone")) {
                        try {
                            ZoneId.of(v);
                        } catch (Exception e) {
                            throw invalid("không phải múi giờ hợp lệ (vd Asia/Ho_Chi_Minh)");
                        }
                    }
                    if (v.isEmpty() || v.length() > 100) {
                        throw invalid("không được trống và tối đa 100 ký tự");
                    }
                    return v;
                }
            }
        }

        private ApiException invalid(String reason) {
            return new ApiException(ErrorCode.VALIDATION_ERROR, label + " " + reason);
        }
    }

    public static final List<Def> ALL = List.of(
            new Def("station.hold_minutes", "Trạm", "Thời gian giữ chỗ tại trạm (phút)",
                    "Kiện hàng được giữ chỗ tại trạm tối đa bao lâu trước khi bị giải phóng.",
                    "INT", "120", 5, 1440),
            new Def("delivery.max_retry", "Giao hàng", "Số lần giao thử lại tối đa",
                    "Sau số lần giao thất bại này, đơn chuyển sang hoàn về thay vì giao lại.",
                    "INT", "2", 0, 10),
            new Def("drone.battery_warning_percent", "Drone", "Ngưỡng pin cảnh báo (%)",
                    "Cảnh báo khi pin drone xuống dưới mức này.",
                    "INT", "25", 5, 80),
            new Def("system.timezone", "Hệ thống", "Múi giờ hệ thống",
                    "Múi giờ dùng để hiển thị và lên lịch (định dạng IANA).",
                    "STRING", "Asia/Ho_Chi_Minh", null, null),
            new Def("notification.enabled", "Thông báo", "Bật thông báo trong ứng dụng",
                    "Gửi thông báo trạng thái đơn hàng cho khách hàng trong ứng dụng.",
                    "BOOLEAN", "true", null, null),
            new Def("notification.email_enabled", "Thông báo", "Bật thông báo qua email",
                    "Gửi thêm email khi trạng thái đơn hàng thay đổi.",
                    "BOOLEAN", "false", null, null));

    public static final Map<String, Def> BY_KEY =
            ALL.stream().collect(Collectors.toMap(Def::key, Function.identity()));
}
