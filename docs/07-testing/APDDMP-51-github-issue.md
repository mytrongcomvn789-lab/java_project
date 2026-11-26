# [Draft] Issue 7.1 (APDDMP-51): Thiết kế AI Service (module nội bộ + Gemini)

> Bản nháp để tự đăng. Chưa gọi `gh`.

## Đã làm

- Quyết định kiến trúc: module nội bộ `com.smartdrone.application.ai` trong monolith, ranh giới rõ (chỉ phụ thuộc nghiệp vụ qua `AiDataAccessGuard` → `OrderQueryService`/`StatisticsService`), có thể tách service sau mà không đổi hợp đồng. Bảng so sánh và sơ đồ Mermaid: `docs/03-architecture/ai-service.md`.
- Khung code: `AiProvider`, `AiRequest/AiResponse/AiResult`, `AiTask`, `AiService`, `AiDataAccessGuard`, `PromptBuilder`, `AiOutputValidator`, `AiProperties` (`smartdrone.ai.*`), `AiRateLimiter`, `AiBudget`, `AiCircuitBreaker`.
- Ba provider: `DisabledAiProvider`, `MockAiProvider` (chỉ profile dev/test/h2/demo, luôn `mock=true`), `GeminiAiProvider` (`RestClient`, không SDK, key ở header).
- Mã lỗi mới: `AI_NOT_CONFIGURED` 503, `AI_PROVIDER_UNAVAILABLE` 503, `AI_TIMEOUT` 504, `AI_RATE_LIMITED` 429, `AI_BUDGET_EXCEEDED` 429, `AI_RESPONSE_INVALID` 502.
- Controller khung `/v1/ai/**` (ETA, summary, chat, analytics) theo hợp đồng nháp; đăng ký ở `RbacPolicy` + `@PreAuthorize` + `rbac-matrix.csv`. API key 5.4 không dùng được.
- Tài liệu: `docs/03-architecture/ai-service.md`, `docs/05-api/ai-api.md`, `docs/08-deployment/ai-configuration.md`, `docs/07-testing/APDDMP-51-verification.md`.

## Kiểm thử

`mvn -B test`: 734 test, 0 lỗi, 9 bỏ qua (7 MinIO, 2 cần `ORDER_TEST_DATABASE_URL`). 45 test mới ngoài ma trận RBAC, không gọi mạng thật. Chi tiết: `docs/07-testing/APDDMP-51-verification.md`.

## Bằng chứng tích hợp (Gemini thật)

Chưa có. Người chạy điền bảng ở mục 3 của file xác nhận sau khi thử với khóa thật.

## Bàn giao cho 7.2–7.6

- Mỗi task chỉ thêm: khối dữ liệu trong `AiDataAccessGuard`, chỉ dẫn/schema trong `PromptBuilder` + `AiOutputValidator`, và thay phần khung của `AiController`. Không đụng provider/rate limit/ngân sách.
- Hợp đồng `/v1/ai/**` là **bản nháp, chờ chốt** với chủ từng task.
- 7.2 (ETA): hiện chỉ gửi trạng thái, mốc thời gian, trạm, kích thước gói; chưa có khoảng cách/lịch giao.
- 7.4 (chat): `conversationId` chỉ là mã tương quan; **chưa lưu hội thoại**, cần quyết định nơi lưu.
- 7.5/7.6: chỉ dùng số liệu tổng hợp từ `StatisticsService`.

## Cần chốt

1. Tên model mặc định (`gemini-2.5-flash-lite`).
2. Ngân sách và rate limit mặc định (ước lượng, chưa đối chiếu AI Studio).
3. Nơi lưu hội thoại chat (7.4).
4. Dùng gói miễn phí với dữ liệu thật hay chuyển gói trả phí trước khi chạy thật.
5. Có cần bộ đếm tập trung nếu chạy nhiều instance.
