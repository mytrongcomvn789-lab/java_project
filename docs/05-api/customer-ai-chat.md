# 7.4 — Trợ lý AI khách hàng (APDDMP-54)

Phụ trách: **Lê Công Nhã**. Nền tích hợp: main e0a44bc, AI Service 7.1 (PR #53).
Ngày triển khai: 25/11/2026. Backend Java Spring Boot; dùng AiService/Gemini đã có.
Giao diện web/Flutter thuộc 7.6, không nằm trong thay đổi này.

## Mục tiêu và phạm vi

Chat hỏi đáp chỉ đọc về dữ liệu đơn mà khách hàng được phép xem và nguồn quy tắc có thể kiểm chứng.
Không có công cụ cho model hủy đơn, đổi quyền, xác nhận giao hàng, SQL hay HTTP tùy ý.
Không khai báo đây là hệ thống điều khiển drone thật.

## Hợp đồng API

`POST /api/v1/ai/chat`, JWT CUSTOMER. API key tích hợp 5.4 không thay thế JWT.

Request đầu tiên:

```json
{"message":"Đơn của tôi đang ở trạng thái nào?","orderId":"<UUID đơn của chính khách hàng>"}
```

Hỏi quy tắc chung có thể bỏ orderId. Tiếp tục hội thoại:

```json
{"message":"Tôi cần làm gì tiếp?","conversationId":"<UUID server trả về>"}
```

- conversationId do server tạo. ID không tồn tại, hết hạn hoặc của tài khoản khác đều 404.
- Bỏ orderId ở lượt tiếp theo: dùng lại đơn đã gắn. Muốn đổi đơn phải tạo hội thoại mới.
- Không tự đọc UUID trong nội dung câu hỏi để lấy đơn; chỉ dùng orderId đã qua kiểm quyền.
- Tin nhắn bắt buộc không rỗng, tối đa theo `smartdrone.ai.max-chat-message-chars` (mặc định 1000).
- Server kiểm lại quyền xem đơn ở MỖI lượt, rồi mới gửi dữ liệu tối thiểu cho provider.
- Không nhận accountId, role hoặc lịch sử do client tự khai.

Response giữ envelope AiResult của 7.1, bổ sung nội dung chat trong result:

```json
{
  "result": {
    "reply": "[MOCK] Câu trả lời giả lập, không phải kết quả AI thật.",
    "conversationId": "<UUID server>",
    "orderId": "<UUID hoặc null>",
    "knowledgeVersion": "functional-rules-2026-11-25-v1",
    "readOnly": true,
    "sources": [{"id":"scope","title":"Phạm vi dữ liệu khách hàng","reference":"OrderQueryService.get"}]
  },
  "model": "mock",
  "mock": true,
  "degraded": false,
  "source": "MOCK",
  "usage": {"promptTokens":0,"outputTokens":0,"totalTokens":0},
  "requestId": "<UUID>",
  "generatedAt": "<ISO-8601>",
  "fallbackReason": null
}
```

Ví dụ rút gọn danh sách sources; xem response thực tế để lấy đủ catalog.
`sources` là danh mục quy tắc hệ thống được cung cấp, KHÔNG phải chứng minh từng câu LLM đều đúng.
Chỉ `source=PROVIDER` (theo enum của 7.1), `mock=false`, `degraded=false` mới là phản hồi provider thật.
Luôn dùng giá trị enum thực tế do backend trả, không hardcode tên model ở client.

## Khác biệt so với khung 7.1

1. CHAT chỉ còn CUSTOMER; DISPATCHER/STATION_OPERATOR nhận 403. ETA/summary giữ quyền cũ.
2. conversationId không còn được echo tùy ý từ client: được kiểm chủ sở hữu và lưu DB.
3. Khi bật fallback và provider lỗi: result có reply giải thích + conversationId, source=FALLBACK,
   degraded=true, fallbackReason giữ nguyên. Các tác vụ AI khác giữ hợp đồng fallback của 7.1.
4. Chỉ trả các trường chat được server cho phép; bỏ toolCall, status và ID do model tự sinh.
5. Câu trả lời là **văn bản không tin cậy**: frontend phải render text, không HTML và không thực thi lệnh.

## Hội thoại và đồng thời

Bảng mới `ai_chat_conversations`: id, owner_id, order_id, expires_at, version, lease, busy_until,
turns, history (Unicode JSON).
Thời hạn cố định 24 giờ; mỗi giờ dọn hội thoại hết hạn. Giữ 3 cặp hỏi/đáp gần nhất,
tối đa 30 lượt thành công rồi yêu cầu tạo hội thoại mới. PromptBuilder 7.1 còn giới hạn từng block.
Không trả endpoint liệt kê lịch sử trong 7.4; client giữ conversationId theo tài khoản và xóa state khi logout.

Mỗi request nhận lease 180 giây trong transaction ngắn. Trong lúc gọi Gemini không giữ khóa DB.
Request trùng hội thoại đang xử lý trả 409. Response đến muộn không ghi đè request có lease mới.
Client phải khóa nút gửi khi chờ; timeout không được tự gửi lại vô hạn.
Chưa có idempotency-key cho chat: khi mất response sau thành công, retry có thể phát sinh một lượt/chi phí mới.

## Nguồn kiến thức và giới hạn

ChatKnowledge v1 chỉ chứa quy tắc chức năng đang có: phạm vi đơn của chính mình, chat chỉ đọc,
OTP/QR phải xác minh qua máy chủ. Chưa có bộ chính sách thương mại được nhóm duyệt.
Câu hỏi giá/phí/bồi thường/bảo hiểm/cam kết thời gian phải báo thiếu thông tin và hướng người dùng
tới nhóm vận hành. Trước khi mở rộng chính sách, chủ dự án duyệt nội dung, tài liệu nguồn và tăng version.

Không gửi snapshot địa chỉ, người nhận, mật khẩu hoặc OTP từ DB. Câu hỏi/lịch sử/câu trả lời
được redactor của 7.1 xử lý trước khi lưu/gửi/trả. Redaction theo pattern không bảo đảm nhận ra mọi
tên, địa chỉ hay bí mật người dùng tự gõ; UI cần nhắc không nhập thông tin nhạy cảm.
Prompt tách system instruction với dữ liệu; lịch sử không phải nguồn trạng thái hiện tại.
Prompt injection có thể tác động câu chữ của model, nhưng không cấp quyền đọc đơn khác hay thực thi nghiệp vụ.
Chưa kết luận độ đúng ngữ nghĩa của Gemini bằng test mock.

## Mã lỗi

| HTTP | Trường hợp |
|---|---|
| 400 | Thiếu/quá dài message, đổi order của hội thoại, đủ 30 lượt |
| 401 | Thiếu JWT/không hợp lệ; API key không thay JWT |
| 403 | Vai trò không phải CUSTOMER |
| 404 | Đơn/hội thoại không thuộc tài khoản, không tồn tại hoặc hết hạn |
| 409 DATA_CONFLICT | Hội thoại đang xử lý hoặc request đã mất lease |
| 429 | Rate limit/ngân sách theo 7.1; không chuyển thành fallback |
| 503/504 | Provider thiếu cấu hình/lỗi/timeout khi không bật fallback |

## Cấu hình và chạy

Giữ cấu hình `smartdrone.ai.*` theo [ai-configuration.md](../08-deployment/ai-configuration.md).
Không tạo provider thứ hai hoặc khóa OpenAI. Không commit khóa Gemini.
Mock chỉ dùng profile dev/test theo kiểm tra AiConfig; phải ghi rõ kết quả giả lập.
Để nghiệm thu thật cần cấu hình Gemini do nhóm quản lý và bộ câu hỏi có đáp án chuẩn được duyệt.

```powershell
cd backend
mvn "-Dtest=AiApiIntegrationTest,AiFallbackIntegrationTest,AiNotConfiguredIntegrationTest,AiServiceTest,PromptBuilderTest,GeminiAiProviderTest,RbacIntegrationTest" test
mvn test
```

Schema Hibernate hiện là ddl-auto=update trong môi trường dev của repository.
Trước deploy dữ liệu thật: backup, rà DDL SQL Server, chỉ áp dụng qua quy trình triển khai của nhóm.
H2 test không thay thế kiểm tra SQL Server thật.

## Bàn giao cho 7.6

Hiển thị nguồn MOCK/FALLBACK rõ ràng, loading/error/429/retry; render reply dạng text.
Không tạo UI cho operator gọi CHAT. Khi đổi tài khoản xóa conversationId và toàn bộ state cũ.
Chọn đơn mới → tạo hội thoại mới. Hết hạn/30 lượt → cho người dùng bắt đầu lại.
Không hiển thị “đã hủy/đã giao” chỉ dựa vào câu chatbot; trạng thái lấy từ API đơn.
