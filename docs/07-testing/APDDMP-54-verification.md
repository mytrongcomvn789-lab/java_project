# APDDMP-54 — Kết quả kiểm thử và bàn giao 7.4

Phụ trách: **Lê Công Nhã**. Ngày: 25/11/2026.
Nhánh: `feature/7.4-customer-ai-chat`; nền `origin/main e0a44bc` (đã merge 7.1).
Môi trường: Windows, JDK 25, Maven, Spring Boot 4.0.5, H2 ở profile kiểm thử.
Test provider dùng fixture/mock và HTTP server cục bộ; KHÔNG gọi Gemini thật.

## Kết quả cuối

`mvn -f backend/pom.xml test`: **BUILD SUCCESS**, tổng 745, failures 0, errors 0, skipped 9.
Có **736 test thực chạy đạt**. Không coi skipped là pass.

| Bộ test liên quan | Số test | Kết quả |
|---|---:|---|
| AiApiIntegrationTest | 18 | Đạt, gồm 11 ca mới cho 7.4 |
| AiFallbackIntegrationTest | 1 | Đạt |
| AiNotConfiguredIntegrationTest | 1 | Đạt |
| AiServiceTest | 15 | Đạt |
| PromptBuilderTest | 7 | Đạt |
| GeminiAiProviderTest | 14 | Đạt |
| RbacIntegrationTest | 292 | Đạt |
| Tổng nhóm AI + RBAC | 348 | 0 lỗi, 0 skip |

9 ca bị skip do điều kiện môi trường có sẵn: 7 MinioFileFlowTest thiếu MINIO_TEST_ENDPOINT;
2 test concurrent SQL trong OrderIntegrationTest thiếu ORDER_TEST_DATABASE_URL.
Không thay điều kiện skip và không tắt test để đạt kết quả.

Lượt đầu nhóm AI/RBAC có một failure do test khung 7.1 mong result=null khi fallback.
Hợp đồng chat 7.4 trả reply + conversationId trong fallback; đã cập nhật assertion kiểm nội dung,
ID và tiếp tục giữ kiểm tra không lộ key. Full suite sau thay đổi đã đạt.

## Các ca 7.4 đã thực chạy

| Ca | Kiểm tra và kết quả mong đợi |
|---|---|
| Hội thoại của tài khoản khác | 404, không gọi provider |
| UUID hội thoại tự nhập/không tồn tại | 404, không tạo hội thoại theo ID client |
| Dispatcher/operator | 403; chỉ CUSTOMER dùng chat |
| Tiếp tục hội thoại | Bỏ orderId vẫn lấy lại đơn cũ, đọc trạng thái mới từ DB |
| Đổi đơn trong hội thoại | 400, không gọi provider |
| Prompt injection / thông tin nhạy cảm | Không đưa user text vào system instruction; escape delimiter và redact trước gửi/lưu |
| Hai request cùng hội thoại | Lease đang dùng → 409, không thêm lượt gọi provider |
| Provider timeout | 504 khi fallback tắt, giải phóng lease để retry |
| Giới hạn lịch sử / hết hạn | Chỉ giữ 3 lượt; hết 24h → 404; cleanup xóa được |
| Request cũ đến muộn | Không ghi đè hoặc giải phóng lease mới |
| 30 lượt thành công | Yêu cầu tạo hội thoại mới, không gọi provider lần 31 |
| Quyền đơn thay đổi giữa hai lượt | Đọc lại quyền → 404, không dùng snapshot cũ |
| Model trả toolCall/status/ID tùy ý | Loại khỏi payload; đơn vẫn PENDING; ID do server quyết định; reply được che thông tin |
| Fallback | source=FALLBACK, degraded=true, có lời giải thích + conversationId |
| Thiếu cấu hình AI | 503 AI_NOT_CONFIGURED khi fallback tắt |

Các ca nằm trong 11 phương thức mới; một phương thức có thể kiểm nhiều tình huống.
Test prompt/injection xác nhận ranh giới dữ liệu và quyền thực thi, không chứng minh model thật
luôn trả lời đúng ngữ nghĩa.

## Các commit theo chức năng

1. `92fa3b1`: entity/repository/store hội thoại; hết hạn, lease và transaction ngắn.
2. `80ac3b0`: nguồn quy tắc có version và hướng dẫn chatbot chỉ đọc.
3. `183c4d3`: CustomerChatService, endpoint, CUSTOMER-only, redaction và fallback; đồng bộ test hợp đồng/RBAC.
4. `c1897eb`: các test tích hợp bổ sung cho hội thoại/quyền/dữ liệu/lỗi.
5. Commit docs: API, sơ đồ, báo cáo này và nội dung issue.

Không tách mỗi file thành một commit và không tạo commit rỗng.

## Cần nghiệm thu tiếp

- Chạy với SQL Server thật, kiểm tra DDL bảng hội thoại và hai ca concurrency yêu cầu môi trường DB.
- Chạy các test MinIO khi có storage.
- Nhóm duyệt bộ chính sách thương mại nếu muốn trả lời giá/phí/bồi thường; hiện chỉ có quy tắc chức năng.
- Kiểm thử Gemini thật bằng dữ liệu giả được phép chia sẻ, bộ câu hỏi chuẩn và ngân sách nhóm.
- Tích hợp UI thuộc 7.6 của Trọng; chưa tuyên bố đã có màn chat web/mobile trong PR này.
- Reviewer kiểm tra trước merge/Done.

Xem [API và hướng dẫn 7.6](../05-api/customer-ai-chat.md) và [sơ đồ](../03-architecture/customer-ai-chat.md).
