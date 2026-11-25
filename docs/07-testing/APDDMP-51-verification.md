# APDDMP-51 (task 7.1) — Xác nhận kiểm thử AI Service

Nhánh `feature/7.1-ai-service` (từ `origin/main`). Hợp đồng và thiết kế: `docs/05-api/ai-api.md`, `docs/03-architecture/ai-service.md`.

## 1. Đã chạy (kết quả thật)

Lệnh: `cd backend; mvn -B test` (ngày 2026-11-25, JDK 21, H2).

**Kết quả: `Tests run: 734, Failures: 0, Errors: 0, Skipped: 9` — `BUILD SUCCESS`.**

9 test bị bỏ qua, không liên quan 7.1:
- `MinioFileFlowTest`: 7 (không có MinIO trong môi trường chạy).
- `OrderIntegrationTest`: 2 (cần biến `ORDER_TEST_DATABASE_URL`, không đặt).

Test mới của 7.1 (không gọi mạng thật; `GeminiAiProvider` được test với máy chủ HTTP cục bộ của JDK, không thêm thư viện):

| Lớp test | Số test | Nội dung chính |
|---|---|---|
| `GeminiAiProviderTest` | 14 | 200 hợp lệ (đọc đúng text, `usageMetadata`, model); khóa ở header `x-goog-api-key`, không ở URL/body; `systemInstruction` tách khỏi dữ liệu người dùng; thiếu khóa không gọi mạng; 5xx retry đúng 2 lần rồi lỗi; retry thành công sau lỗi tạm; 400/401/403/404 không retry; 429 không có `Retry-After` không retry; 429 có `Retry-After` retry 1 lần đúng độ trễ; `Retry-After` quá dài không retry; timeout đọc → `AI_TIMEOUT` và retry trong giới hạn; SAFETY/blockReason/finishReason lạ, JSON hỏng, candidates rỗng → `AI_RESPONSE_INVALID`; cổng đóng → `AI_PROVIDER_UNAVAILABLE`; `toString` không in khóa |
| `AiServiceTest` | 15 | Envelope; sai vai trò → 403 và không gọi provider; rate limit (theo người dùng); `AI_BUDGET_EXCEEDED`; lỗi thì trả lại ngân sách; lỗi provider/exception lạ → mã `AI_*`, không 500; fallback `degraded=true`; fallback không che lỗi quyền/rate limit; sai schema; circuit breaker; chỉ dẫn tách dữ liệu; audit chỉ metadata; lỗi audit không làm hỏng request; Disabled; Mock gắn `mock=true` |
| `PromptBuilderTest` | 7 | Chỉ dẫn hệ thống không đổi theo dữ liệu (mọi task); chuỗi tiêm lệnh nằm trong khối có nhãn và không thể đóng khối; che email/SĐT/dãy số/token; token luôn bị che; cắt khối quá dài; nhãn không bị tiêm; kiểm schema đầu ra |
| `AiApiIntegrationTest` | 7 | 401 khi không đăng nhập và khi chỉ gửi `X-Api-Key`; 403 sai vai trò (CUSTOMER → analytics, MANAGER/ADMIN → chat/summary); CUSTOMER xem đơn mình, người khác/đơn không tồn tại → 404 và provider không bị gọi; nhân viên chưa phân công trạm → 404; chat kiểm độ dài/rỗng; analytics qua thống kê; timeout → 504, lỗi → 503, ngoại lệ lạ → 503 không lộ chi tiết |
| `AiNotConfiguredIntegrationTest` | 1 | Không có khóa, provider DISABLED → 503 `AI_NOT_CONFIGURED` đúng khuôn lỗi |
| `AiFallbackIntegrationTest` | 1 | Gemini trỏ vào cổng đóng + fallback bật → 200 `degraded=true`; khóa không xuất hiện trong response |
| `RbacIntegrationTest` (thêm 4 dòng vào `rbac-matrix.csv`) | 292 (tổng) | Ma trận 5 vai trò × 4 route `/v1/ai/**`, và 401 khi ẩn danh |

## 2. Chưa kiểm chứng (nói thẳng)

- **Chưa gọi Gemini thật.** Test tự động không dùng mạng; hình dạng request/response được dựng theo tài liệu Gemini `generateContent` (header `x-goog-api-key`, `systemInstruction`, `contents`, `generationConfig.responseMimeType`, `usageMetadata`) nhưng chưa đối chiếu với phản hồi thật. Cần chạy mục 3.
- Tên model mặc định `gemini-2.5-flash-lite` và các hạn mức mặc định là ước lượng, chưa đối chiếu với AI Studio.
- Rate limit/ngân sách/ngắt mạch chỉ đúng cho một instance (in-memory).
- Chưa kiểm chứng trên SQL Server (test chạy H2).
- Chưa có test cho việc `RestClient` bị `Interrupted` giữa lúc chờ retry, và chưa test tải đồng thời.

## 3. Thử thủ công với khóa thật (bằng chứng tích hợp — phần kết quả do người chạy điền)

Đây **không** phải test tự động.

PowerShell:

```powershell
$env:SMARTDRONE_AI_GEMINI_API_KEY = "<khóa-của-bạn>"   # không commit, không dán vào chat/log
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=h2"
```

Ở cửa sổ khác: đăng nhập khách hàng, tạo đơn, rồi gọi tóm tắt (thay `<TOKEN>`, `<ORDER_ID>`):

```powershell
curl.exe -s -X POST "http://localhost:5000/api/v1/ai/orders/<ORDER_ID>/summary" -H "Authorization: Bearer <TOKEN>"
```

Kỳ vọng: HTTP 200, `source":"PROVIDER"`, `mock":false`, `degraded":false`, `model` = tên model đã cấu hình, `usage.totalTokens` > 0. Không có khóa → 200 với `mock:true` (profile h2), không phải kết quả AI thật.

| Mục | Kết quả (người chạy điền) |
|---|---|
| Ngày/giờ chạy | |
| Model thực tế | |
| HTTP status | |
| `source` / `mock` / `degraded` | |
| `usage.totalTokens` | |
| Khóa không xuất hiện trong response và log (đã kiểm) | |
| Ghi chú (lỗi 429/404 model...) | |
