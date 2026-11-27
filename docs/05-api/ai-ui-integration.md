# 7.6 — Tích hợp AI vào web và mobile (APDDMP-56)

Phụ trách: **Trọng Lê Mỹ**. Ngày: 26/11/2026. Nhánh: `feature/7.6-ai-web-mobile` (nền `main` `d799e80`).
Backend: AI Service 7.1, ETA 7.2, tóm tắt 7.3, chat 7.4, phân tích 7.5. Hợp đồng API ở [ai-api.md](ai-api.md),
[customer-ai-chat.md](customer-ai-chat.md), [operations-ai-analytics.md](operations-ai-analytics.md), [eta-api.md](eta-api.md).

> Client chỉ gọi **API backend bằng JWT của người dùng**. Không có khóa hay URL nhà cung cấp AI trong web/mobile.
> Mọi kết quả đều hiển thị kèm **nhãn nguồn**; chỉ `source=PROVIDER`, `mock=false`, `degraded=false` mới được gọi là AI thật.

## 1. Các màn hình

| Tính năng | Web (React) | Mobile (Flutter) | Vai trò |
|---|---|---|---|
| ETA | Panel trong chi tiết đơn (`OrderEtaPanel`), tự tải | Panel trong `OrderDetailScreen` (`AiEtaPanel`), tự tải | CUSTOMER, DISPATCHER, STATION_OPERATOR (web); CUSTOMER (mobile) |
| Tóm tắt đơn | Panel trong chi tiết đơn (`OrderSummaryPanel`), chạy khi bấm nút | `AiSummaryPanel`, chạy khi bấm nút | như trên |
| Chat | Trang `/assistant` (`AssistantPage`) | Màn `/customer/assistant` (`AssistantScreen`) | CUSTOMER |
| Phân tích vận hành | Trang `/ai-analytics` (`OperationsAnalyticsPage`): mục menu "Phân tích vận hành (AI)", có thêm link từ trang báo cáo | Không làm (mobile không hỗ trợ vai trò quản lý) | LOGISTICS_MANAGER |

Web: `web/src/lib/aiService.ts` (lớp gọi API, kiểu dữ liệu, nhãn nguồn, map lỗi), `web/src/lib/useAiTask.ts` (hook dùng chung).
Mobile: `mobile/lib/features/ai/` (`ai_api`, `ai_models`, `ai_errors`, `ai_task_controller`, `ai_panels`), `mobile/lib/features/assistant/`.

## 2. Quy tắc hiển thị nguồn kết quả

| `source` | Nhãn | Ý nghĩa |
|---|---|---|
| `FORMULA` | Ước tính theo công thức | ETA 7.2. Không phải AI/ML, độ tin cậy luôn thấp |
| `MOCK` (hoặc `mock=true`) | Giả lập (MOCK) | Fixture dev/test, không phải AI thật |
| `FALLBACK` (hoặc `degraded=true`) | Dự phòng (không dùng AI) | Nội dung do hệ thống sinh theo quy tắc cố định; hiện lý do từ `fallbackReason` |
| `PROVIDER` | AI (tên model) | Kết quả AI thật đã qua kiểm tra của backend |
| khác | Không rõ nguồn | Không bao giờ coi là AI thật |

Tên model không hardcode ở client; luôn lấy từ `model` do backend trả.

## 3. Trạng thái, lỗi, thử lại, hủy

| Mã/HTTP | Hiển thị | Cho "Thử lại" |
|---|---|---|
| Mất mạng / timeout phía client (30 giây) | Báo không kết nối; chat cảnh báo "có thể đã được xử lý" | Có |
| 401 | Phiên hết hạn (client đã thử refresh một lần) | Không |
| 403 | Không có quyền dùng tính năng | Không |
| 404 | Không tìm thấy / hết hạn / ngoài quyền; chat đề nghị "Cuộc trò chuyện mới" | Không |
| 400 | Dữ liệu không hợp lệ (chat: đủ lượt/đổi đơn → hội thoại mới) | Không |
| 409 (chat bận) | Đợi giây lát | Có |
| 429 `AI_RATE_LIMITED`, `AI_BUDGET_EXCEEDED` | Báo hạn mức | **Không** (thử lại chỉ tốn thêm hạn mức) |
| 503 `AI_NOT_CONFIGURED` | AI chưa cấu hình | **Không** |
| `AI_TIMEOUT`, `AI_PROVIDER_UNAVAILABLE`, `AI_RESPONSE_INVALID`, 5xx | Thông báo theo mã | Có |

- Thử lại do **người dùng bấm**, tối đa **3 lần thất bại liên tiếp**; sau đó chỉ còn "Cuộc trò chuyện mới"/tải lại. Không tự động thử lại.
- Tóm tắt, phân tích và chat chỉ chạy khi người dùng bấm (có thể tốn hạn mức AI). ETA tự tải (công thức, không tốn token).
- Rời màn hình/đổi đơn: hủy yêu cầu đang chờ (`AbortSignal` ở web, `CancelToken` ở mobile) và bỏ phản hồi muộn.
- **Đăng xuất/hết phiên/đổi tài khoản:** hủy yêu cầu và xóa mọi dữ liệu AI (kể cả `conversationId`). Hội thoại chỉ nằm trong bộ nhớ, không lưu localStorage hay đĩa.
- Chat: server không cho đổi đơn trong cùng hội thoại, nên chọn đơn khác = hội thoại mới. Chưa có idempotency-key cho chat: gửi lại sau khi mất phản hồi có thể tạo thêm một lượt.
- Toàn bộ văn bản do AI/server sinh hiển thị dạng **text thuần** (không HTML, không liên kết do AI tạo).
- Trạng thái đơn chính thức luôn lấy từ API đơn, không dựa vào câu trả lời chatbot; giao diện nhắc điều này.
- Web chế độ demo (`IS_DEMO_AUTH`, không có token thật) hiển thị yêu cầu đăng nhập tài khoản thật.

## 4. Giới hạn đã biết

- Giới hạn 1000 ký tự chat là hằng số ở client, khớp `smartdrone.ai.max-chat-message-chars` mặc định; server vẫn kiểm tra lại.
- Danh sách đơn để gắn vào chat chỉ lấy 50 đơn gần nhất.
- Mobile chưa có màn phân tích cho quản lý.
- Timeout 30 giây cho request AI (web `AI_TIMEOUT_MS`, mobile `aiReceiveTimeout`) lớn hơn timeout mặc định của API thường.
- Các nhãn cảnh báo/lý do tiếng Việt do 7.6 soạn; cần chủ dự án đọc lại.
- Model Gemini mặc định cũ (`gemini-2.5-flash-lite`) đã bị Google ngừng cấp cho khóa mới (404); mặc định hiện là `gemini-3.5-flash-lite`, vẫn có thể ghi đè bằng `SMARTDRONE_AI_GEMINI_MODEL`. Xem [APDDMP-56-verification.md](../07-testing/APDDMP-56-verification.md) mục 5.

## 5. Chạy và kiểm thử

```powershell
# Web
cd web
npm ci
npm run build          # tsc --noEmit + vite build

# Flutter
cd mobile
flutter analyze --no-pub
flutter test --no-pub  # --no-pub để không đổi pubspec.lock

# Backend demo không cần SQL Server (dữ liệu trong RAM, provider MOCK vì không có khóa Gemini)
cd backend
mvn -o spring-boot:run "-Dspring-boot.run.profiles=h2,demo"
```

Kết quả đã chạy và bằng chứng: [APDDMP-56-verification.md](../07-testing/APDDMP-56-verification.md).

## 6. Bàn giao

- **8.1 (tích hợp toàn hệ thống, APDDMP-58):** cần kiểm tra thêm trên SQL Server thật và đánh giá chất lượng Gemini có hệ thống (mới thử tay vài mẫu với `gemini-3.5-flash-lite`); Flutter chưa chạy trên thiết bị.
- **8.3 (kiểm thử chức năng/phân quyền/hiệu năng, APDDMP-60):** ma trận quyền AI ở mục 1; bộ kiểm tra API ở `docs/07-testing/APDDMP-56-evidence/`. Hiệu năng AI (nhiều user đồng thời, rate limit 5 yêu cầu/phút/người) chưa đo.
- **8.6 (responsive web, rà soát UI/UX, APDDMP-63):** các màn web mới (`/assistant`, `/ai-analytics`, hai panel trong chi tiết đơn) đã được người phụ trách thử tay trên Chrome nhưng **chưa rà responsive và UI/UX kỹ**; mobile **chưa chạy trên thiết bị/emulator**.
