package com.smartdrone.application.ai.chat;

import java.util.List;
import org.springframework.stereotype.Component;
import com.smartdrone.application.ai.AiDataBlock;

/** Technical rules traceable to existing code, not invented commercial policies. */
@Component
public class ChatKnowledge {
    public static final String VERSION = "functional-rules-2026-11-25-v1";
    public record Source(String id, String title, String reference) { }
    public List<Source> sources() {
        return List.of(
            new Source("scope", "Phạm vi dữ liệu khách hàng", "OrderQueryService.get"),
            new Source("read-only", "Chat chỉ hỏi đáp", "CustomerChatService"),
            new Source("pickup", "Mã nhận hàng cần máy chủ xác minh", "PickupChallengeService"));
    }
    public AiDataBlock block() {
        return new AiDataBlock("verified_functional_rules", """
            Nguồn: functional-rules-2026-11-25-v1, chỉ các quy tắc chức năng đã triển khai.
            Khách hàng chỉ được tra cứu đơn của mình sau khi đăng nhập.
            Chatbot chỉ trả lời, không thực hiện hủy đơn, đổi quyền hoặc xác nhận đã giao.
            Mã OTP/QR phải được xác minh qua luồng nhận hàng trên máy chủ; không gửi mã vào chat.
            Chưa có nguồn chính sách thương mại được nhóm phê duyệt trong task này.
            Không có căn cứ về giá, phí, bồi thường, bảo hiểm hay cam kết thời gian giao.
            Với các chính sách chưa có: nói chưa có thông tin xác nhận và đề nghị liên hệ nhóm vận hành.
            Nếu không có khối order: đề nghị chọn đơn trước khi hỏi trạng thái cụ thể.
            """);
    }
}
