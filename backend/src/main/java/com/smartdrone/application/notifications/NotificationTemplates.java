package com.smartdrone.application.notifications;

import java.util.UUID;

import com.smartdrone.domain.enums.OrderStatus;

/**
 * Nội dung thông báo theo event (tên OrderEvent). Cố ý KHÔNG có người nhận, điện thoại, địa chỉ, lý do:
 * khách cần chi tiết thì mở đơn (REST có kiểm tra quyền).
 */
public final class NotificationTemplates {
    public static final String FALLBACK_TITLE = "Cập nhật đơn hàng";

    public record Content(String title, String body) { }

    private NotificationTemplates() { }

    public static String shortCode(UUID orderId) {
        return "#" + orderId.toString().substring(0, 8).toUpperCase();
    }

    public static Content render(String event, OrderStatus status, UUID orderId) {
        String code = shortCode(orderId);
        return switch (event == null ? "" : event) {
            case "ORDER_APPROVED" -> new Content("Đơn hàng đã được duyệt",
                    "Đơn " + code + " đã được duyệt và sẽ sớm được lên lịch giao.");
            case "ORDER_REJECTED" -> new Content("Đơn hàng bị từ chối",
                    "Đơn " + code + " không được duyệt. Mở đơn để xem lý do.");
            case "ORDER_CANCELLED" -> new Content("Đơn hàng đã hủy",
                    "Đơn " + code + " đã được hủy.");
            case "ORDER_SCHEDULED" -> new Content("Đơn hàng đã lên lịch giao",
                    "Đơn " + code + " đã có lịch giao. Mở đơn để xem khung giờ.");
            case "ORDER_PREPARING" -> new Content("Trạm đang chuẩn bị đơn hàng",
                    "Đơn " + code + " đã được trạm tiếp nhận và đang chuẩn bị.");
            case "ORDER_DISPATCHED" -> new Content("Drone đang giao đơn hàng",
                    "Đơn " + code + " đã xuất phát. Mở đơn để theo dõi.");
            case "ORDER_ARRIVED" -> new Content("Đơn hàng đã đến điểm nhận",
                    "Đơn " + code + " đã đến trạm nhận. Vui lòng đến nhận hàng.");
            case "ORDER_DELIVERED" -> new Content("Giao hàng thành công",
                    "Đơn " + code + " đã được giao thành công.");
            case "ORDER_FAILED" -> new Content("Giao hàng thất bại",
                    "Đơn " + code + " giao chưa thành công. Mở đơn để xem chi tiết.");
            case "ORDER_RETURN_STARTED" -> new Content("Đơn hàng đang được hoàn",
                    "Đơn " + code + " đang được hoàn về trạm.");
            case "ORDER_RETURNED" -> new Content("Đơn hàng đã hoàn về trạm",
                    "Đơn " + code + " đã được hoàn về trạm.");
            default -> new Content(FALLBACK_TITLE,
                    "Đơn " + code + " chuyển sang trạng thái \"" + status.label() + "\".");
        };
    }
}