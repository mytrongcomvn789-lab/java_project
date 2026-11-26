## 7.6 — Tích hợp AI vào web và mobile (APDDMP-56)

Phụ trách: Trọng Lê Mỹ. Nhánh: `feature/7.6-ai-web-mobile`, nền `main` `d799e80`. Commit: `9f18dca` (web), `2a9f187` (Flutter), `3ebd7fa` (sửa backend 7.3/7.5). Chưa push, chưa tạo PR.
Jira: APDDMP-56 (To Do; **chưa chuyển Done**, chờ review và nghiệm thu).

> Giao diện cho ETA, tóm tắt, chat và phân tích. Chỉ gọi API backend bằng JWT, không có khóa/provider ở client.
> **Chưa chạy Flutter trên thiết bị/emulator; chưa chạy SQL Server; chưa đánh giá chất lượng Gemini một cách hệ thống.**
> **Có sửa nhỏ ở backend của 7.3 và 7.5** (commit riêng): cần người phụ trách hai task đó review.

### Đã triển khai
- **Web:** ETA và tóm tắt trong chi tiết đơn (CUSTOMER, DISPATCHER, STATION_OPERATOR); trang chat `/assistant` (CUSTOMER); trang phân tích `/ai-analytics` (LOGISTICS_MANAGER, mục menu có sẵn, thêm link từ trang báo cáo).
- **Flutter:** ETA và tóm tắt trong chi tiết đơn; thay màn giữ chỗ trợ lý AI bằng chat thật.
- Nhãn nguồn rõ ràng: công thức (FORMULA), giả lập (MOCK), dự phòng (FALLBACK), AI thật (PROVIDER).
- Loading, lỗi theo mã `AI_*`, thử lại do người dùng bấm tối đa 3 lần, **không thử lại** khi 429/`AI_NOT_CONFIGURED`, hủy yêu cầu khi rời màn hình, xóa dữ liệu và `conversationId` khi đăng xuất/đổi tài khoản.
- Văn bản AI hiển thị dạng text thuần. Tóm tắt, chat và phân tích chỉ chạy khi bấm; ETA tự tải.

### Sửa ở backend của task khác (do phát hiện khi chạy Gemini thật)
- **7.3 (tóm tắt):** mốc thời gian trong prompt đổi từ UTC sang giờ Việt Nam; trước đó văn bản AI lệch 7 tiếng so với timeline.
- **7.5 (phân tích):** Gemini chép câu khuyến nghị vào `recommendations` thay vì trả mã nên phân tích luôn ra Dự phòng (`AI_RESPONSE_INVALID`, 6/6 lần). Khối căn cứ gửi model không còn câu khuyến nghị; chỉ dẫn nêu rõ chỉ trả mã. Bộ kiểm tra không bị nới lỏng.
- Thêm 4 test và sửa 1 test. Chi tiết: [APDDMP-56-verification.md](APDDMP-56-verification.md) mục 4.

### Đã test (26/11/2026)

| Nhóm | Kết quả |
|---|---|
| Web `npm run build` | `tsc --noEmit` sạch, `vite build` thành công |
| `flutter analyze --no-pub` | 0 lỗi; 1 info cũ ở `test/session_test.dart:160` |
| `flutter test --no-pub` | **309/309 đạt** (33 test mới; API giả) |
| Backend `mvn -o test` | **812 test, 0 failure, 0 error, 9 skipped** |
| API thật trên backend `h2,demo`, MOCK | 34/34 đạt |
| API thật, AI tắt + fallback bật / tắt | 4/4 và 4/4 đạt (chat 503 `AI_NOT_CONFIGURED` khi tắt fallback) |
| Thử tay trên web (Chrome), MOCK và FALLBACK | Đạt cho 4 vai trò |
| Thử tay với **Gemini thật** (`gemini-3.5-flash-lite`) | Chat đúng trạng thái đơn; 2 câu ngoài phạm vi trả "chưa có thông tin", không bịa; tóm tắt và phân tích ra nhãn AI sau khi sửa 7.3/7.5 |

Bằng chứng chi tiết, script và ảnh chụp Gemini thật sau khi sửa (tóm tắt khớp giờ timeline; phân tích ra nhãn AI): [APDDMP-56-verification.md](APDDMP-56-verification.md), [APDDMP-56-evidence/](APDDMP-56-evidence/). Các kết quả thử tay khác không có ảnh.
Phát hiện khi thử tay: menu "Phân tích vận hành (AI)" ban đầu vẫn là trang giữ chỗ (route đặt sai); đã sửa. Build/test không bắt được lỗi này.

### Chưa làm / chưa chứng minh
- **Flutter chưa chạy trên thiết bị/emulator** (emulator Android 17 lag, adb offline); chỉ có widget test với API giả. Cần demo thiết bị.
- **Chưa chạy trên SQL Server thật** (không đăng nhập được `sa` vào instance cục bộ); mọi test API chạy trên H2.
- **Gemini thật chỉ thử tay vài mẫu**, một người, một model, dữ liệu demo: không phải đánh giá chất lượng. Chưa chạy lại script API với Gemini sau khi sửa 7.3/7.5.
- Web chưa có test tự động (không có framework test).
- Chưa thử trên backend thật: chat 409, hết hạn 24 giờ, đủ 30 lượt, hết ngân sách token, hai tài khoản khách khác nhau; chưa đo hiệu năng.
- Mobile chưa có màn phân tích cho quản lý. Giới hạn 1000 ký tự chat là hằng số ở client; danh sách đơn gắn vào chat lấy 50 đơn gần nhất.

### Phát hiện cần nhóm xử lý (ngoài 7.6)
- **7.1:** model mặc định `gemini-2.5-flash-lite` trong `application.yml` bị Google ngừng cấp cho khóa mới (404); `gemini-3.5-flash-lite`/`gemini-3.1-flash-lite` dùng được. `gemini-3.5-flash`/`3.8-flash` trả 503/403 chập chờn khi thử.
- **7.4:** trong lần thử, 2/6 lượt chat với Gemini ra `AI_RESPONSE_INVALID`; chưa điều tra.
- `application-sqlserver.yml` ghi mật khẩu `sa` trong file được commit.

### Tài liệu
- [Tích hợp AI web/mobile (7.6)](../05-api/ai-ui-integration.md)
- [Hợp đồng AI](../05-api/ai-api.md), [Chat 7.4](../05-api/customer-ai-chat.md), [Phân tích 7.5](../05-api/operations-ai-analytics.md), [ETA 7.2](../05-api/eta-api.md)

### Checklist review
- [ ] Review code web (`web/src/lib/aiService.ts`, `useAiTask.ts`, panel ETA/tóm tắt, `AssistantPage`, `OperationsAnalyticsPage`).
- [ ] Review code Flutter (`mobile/lib/features/ai/`, `features/assistant/`, thay đổi trong `order_detail_screen.dart`).
- [ ] **Người phụ trách 7.3 và 7.5 review sửa đổi backend** (`OrderSummaryFacts`, `OperationsAnalyticsService`, `PromptBuilder`).
- [ ] Demo Flutter trên thiết bị hoặc emulator.
- [ ] Kiểm tra trên SQL Server thật; đánh giá chất lượng Gemini bằng bộ câu hỏi chuẩn.
- [ ] Chủ dự án đọc lại các nhãn cảnh báo/lý do tiếng Việt do 7.6 soạn.
- [ ] Cập nhật model mặc định (7.1) và xem lại chat chập chờn (7.4).
- [ ] Lưu ý: `mobile/pubspec.lock` có thay đổi cũ không thuộc 7.6, không commit.

### Ghi chú bàn giao
- **8.1 (APDDMP-58):** tích hợp toàn hệ thống cần kiểm tra thêm SQL Server thật và đánh giá Gemini có hệ thống.
- **8.3 (APDDMP-60):** có bộ kiểm tra API phân quyền/rate limit ở `APDDMP-56-evidence/`; hiệu năng chưa đo (giới hạn 5 yêu cầu AI/phút/người dùng).
- **8.6 (APDDMP-63):** các màn web mới đã được thử tay trên Chrome nhưng chưa rà responsive và UI/UX kỹ; mobile chưa xem trên thiết bị.
