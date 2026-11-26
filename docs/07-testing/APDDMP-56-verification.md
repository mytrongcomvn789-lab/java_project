# APDDMP-56 (7.6) — Xác nhận kiểm thử và bằng chứng

Ngày: 26/11/2026. Người thực hiện: Trọng Lê Mỹ. Nhánh `feature/7.6-ai-web-mobile`, nền `main` `d799e80`.
Commit code: `9f18dca` (web), `2a9f187` (Flutter), `3ebd7fa` (sửa backend 7.3/7.5). Chưa push, chưa tạo PR. Có **hai sửa nhỏ ở backend của 7.3 và 7.5** (mục 6) do phát hiện khi chạy Gemini thật.

> **Kết luận ngắn:** web build đạt, Flutter 309 test đạt (API giả), backend 812 test đạt, API thật đã gọi bằng script (H2; MOCK, FALLBACK).
> Người phụ trách đã **tự thử giao diện web trên trình duyệt** với MOCK, FALLBACK và **Gemini thật** (`gemini-3.5-flash-lite`).
> **Chưa** chạy Flutter trên thiết bị/emulator, chưa chạy SQL Server, chưa đánh giá chất lượng Gemini một cách hệ thống.

## 1. Kiểm thử tự động và script (đã chạy)

| Hạng mục | Lệnh | Kết quả |
|---|---|---|
| Web build | `cd web && npm ci && npm run build` | `tsc --noEmit` không lỗi, `vite build` thành công |
| Flutter phân tích | `flutter analyze --no-pub` | 1 info cũ ở `test/session_test.dart:160` (không thuộc 7.6), 0 lỗi, 0 cảnh báo |
| Flutter test toàn bộ | `flutter test --no-pub` | **309 test, tất cả đạt** (33 test mới: `ai_api_test` 11, `ai_panels_test` 11, `assistant_chat_test` 11) |
| Backend test toàn bộ | `cd backend && mvn -o test` | **812 test, 0 failure, 0 error, 9 skipped (có sẵn), BUILD SUCCESS** (sau khi sửa 7.3/7.5) |
| API thật, MOCK | `python -I api_check.py` trên backend `h2,demo` | **34/34 đạt** |
| API thật, AI tắt + fallback bật | `python -I fallback_check.py fallback` | 4/4 đạt |
| API thật, AI tắt + fallback tắt | `python -I fallback_check.py nofallback` | 4/4 đạt |

Tổng kiểm tra API bằng script: **42/42 đạt**. Script ở [APDDMP-56-evidence/](APDDMP-56-evidence/) (`api_check.py`, `fallback_check.py`). Hai script này chạy **trước** khi sửa 7.3/7.5.

### Cách chạy kiểm tra API

```powershell
cd backend
mvn -o spring-boot:run "-Dspring-boot.run.profiles=h2,demo"      # cửa sổ 1; DB trong RAM, provider MOCK (không có khóa)
python -I docs/07-testing/APDDMP-56-evidence/api_check.py         # cửa sổ 2

# Đường dự phòng: khởi động lại backend với
$env:SMARTDRONE_AI_PROVIDER = "DISABLED"; $env:SMARTDRONE_AI_FALLBACK_ENABLED = "true"    # rồi: fallback_check.py fallback
$env:SMARTDRONE_AI_PROVIDER = "DISABLED"; $env:SMARTDRONE_AI_FALLBACK_ENABLED = "false"   # rồi: fallback_check.py nofallback
```

### Chi tiết 34 kiểm tra (provider MOCK; tài khoản `*@demo.local`)

- **ETA:** SCHEDULED/PREPARING/IN_TRANSIT trả 200, `source=FORMULA`, `computed=true`, `confidence=LOW`, đủ các trường client đọc; DELIVERED/PENDING `applicable=false, STATUS_NOT_APPLICABLE`; `Cache-Control: no-store`; đơn không tồn tại 404; manager 403; không JWT 401; dispatcher và operator 200.
- **Tóm tắt:** SCHEDULED và DELIVERED trả 200, `source=MOCK`, có `timeline`; manager 403.
- **Chat:** lượt 1 kèm `orderId` (200, `source=MOCK`, có `sources`); lượt 2 dùng `conversationId`; `conversationId` lạ 404; đổi đơn trong hội thoại cũ 400; tin nhắn rỗng 400; 1001 ký tự 400; dispatcher 403; manager 403; không JWT 401.
- **Phân tích:** manager 200 (`source=MOCK`, `narrativeMode=AI_SELECTED_VERIFIED_EVIDENCE`, 5 evidence, 7 warnings, 4 trạm); `COMPLETED` có khoảng ngày 200; `from>to` 400; hơn 366 ngày 400; customer 403; dispatcher 403.
- **Rate limit:** gọi chat liên tiếp, nhận `429 AI_RATE_LIMITED`.

### Chi tiết 8 kiểm tra đường dự phòng

| Cấu hình | ETA | Tóm tắt | Chat | Phân tích |
|---|---|---|---|---|
| AI tắt, fallback bật | 200 `FORMULA` | 200 `FALLBACK`, `DETERMINISTIC_FALLBACK`, `AI_NOT_CONFIGURED` | 200 `FALLBACK`, có `reply` và `conversationId` | 200 `FALLBACK`, `DETERMINISTIC_FALLBACK`, số liệu đủ |
| AI tắt, fallback tắt | 200 `FORMULA` | 200 `FALLBACK` (đúng docs 7.3) | **503 `AI_NOT_CONFIGURED`** | 200 `FALLBACK` (đúng docs 7.5) |

## 2. Kiểm tra tay trên giao diện web (người phụ trách, 26/11/2026)

Chrome, web `npm run dev` (cổng 5173), backend `h2,demo`. Ảnh chụp **sau khi sửa 7.3/7.5** (Gemini thật) lưu ở [APDDMP-56-evidence/](APDDMP-56-evidence/); các kết quả còn lại ghi nhận theo báo cáo của người phụ trách, **không có ảnh**.

| Nội dung | Kết quả |
|---|---|
| Điều phối viên: chi tiết đơn có panel ETA và tóm tắt | Đạt (ETA có giờ với đơn SCHEDULED/PREPARING/IN_TRANSIT; trạng thái khác báo không áp dụng) |
| Khách hàng: ETA, tóm tắt, trợ lý AI (chọn đơn, đổi đơn, 429, đăng xuất) với MOCK | Đạt |
| Quản lý: trang phân tích (số liệu, tải trạm khung giờ hiện tại, căn cứ, nhãn nguồn) với MOCK | Đạt sau khi sửa lỗi route (mục 4) |
| Đường dự phòng (AI tắt, fallback bật): nhãn **Dự phòng** và lý do ở tóm tắt/chat/phân tích | Đạt |
| **Gemini thật** (`gemini-3.5-flash-lite`, khóa của người phụ trách): chat | Trả lời đúng trạng thái đơn đã chọn (đơn *Bị từ chối*); nhãn **AI (gemini-3.5-flash-lite)** |
| Gemini thật: câu ngoài phạm vi ("phí giao hàng", "bồi thường") | Trả "chưa có thông tin xác nhận, liên hệ nhóm vận hành", **không bịa số tiền hay chính sách** (2 câu) |
| Gemini thật: tóm tắt, **trước** khi sửa 7.3 | Nhãn AI đúng, số phút khớp, nhưng **giờ trong văn bản là UTC (05:41) lệch 7 tiếng** so với timeline (12:41) |
| Gemini thật: phân tích, **trước** khi sửa 7.5 | **6/6 lần** ra Dự phòng `AI_RESPONSE_INVALID` dù Gemini trả lời thành công |
| Gemini thật: tóm tắt, **sau** khi sửa 7.3 | Giờ trong văn bản khớp timeline: đơn *Chờ duyệt* (văn bản 17:07 26/11, timeline 17:07:42); đơn 3 bước (văn bản 17:07/18:07/19:07 ngày 24/11, timeline 17:07:42/18:07:42/19:07:42, "60 phút giữa các bước"). Ảnh: `gemini-summary-pending-local-time.png`, `gemini-summary-3-steps-local-time.png` |
| Gemini thật: phân tích, **sau** khi sửa 7.5 | Nhãn **AI (gemini-3.5-flash-lite)** ở cả hai khoảng ngày thử; số liệu đổi theo bộ lọc (28/10–26/11: 14 đơn; 01/10–25/11: 11 đơn). Ảnh: `gemini-analytics-28-10_26-11.png`, `gemini-analytics-01-10_25-11.png` (ảnh chỉ chụp phần nhãn và số liệu, chưa chụp phần nhận định) |

Giới hạn của bằng chứng Gemini thật: chỉ **một người, vài mẫu**, một model, dữ liệu demo. **Không phải đánh giá chất lượng**; chưa có bộ câu hỏi chuẩn có đáp án.

## 3. Chưa kiểm tra (không được coi là đã đạt)

| Nền tảng/hạng mục | Tình trạng | Lý do |
|---|---|---|
| **Flutter trên thiết bị/emulator** | **Chưa** | Máy ảo `Medium Phone API 37.0` (Android 17) khởi động lag, adb `offline`; đã bỏ qua. Chỉ có widget test với API giả, chưa xem bố cục, bàn phím, màu thật. Cần demo thiết bị khi có môi trường |
| Web test tự động | **Không có** | `web/` không có framework test (vitest/jest) |
| SQL Server thật | **Chưa** | Backend không đăng nhập được `sa` vào instance cục bộ (`Login failed for user 'sa'`); không đoán mật khẩu. Mọi test API chạy trên H2 |
| Chất lượng Gemini có hệ thống | **Chưa** | Chỉ vài mẫu thử tay; chưa có bộ câu hỏi chuẩn; chỉ thử `gemini-3.5-flash-lite` |
| Sau sửa 7.3/7.5: chạy lại `api_check.py` với Gemini | **Chưa** | Script chạy với MOCK trước khi sửa; sau khi sửa chỉ có test đơn vị và thử tay |
| Chat: 409 hội thoại bận, hết hạn 24 giờ, đủ 30 lượt, hết ngân sách token | **Chưa** | Chỉ có test client với API giả |
| Đơn của khách khác bằng hai tài khoản khách | **Chưa** | Seed demo chỉ có một khách |
| Hiệu năng, nhiều user đồng thời | **Chưa** | Thuộc 8.3 |

## 4. Phát hiện và đã sửa trong 7.6

- **Route phân tích bị trỏ nhầm (lỗi của 7.6, phát hiện khi thử tay):** menu quản lý "Phân tích vận hành (AI)" (`/ai-analytics`) vẫn là trang giữ chỗ vì trang mới ban đầu đặt ở đường dẫn khác. Đã chuyển trang về `/ai-analytics`. Build và type-check không bắt được lỗi này.
- **7.3, lệch múi giờ:** prompt đưa mốc thời gian dạng UTC nên văn bản AI lệch 7 tiếng so với timeline. Đã sửa `OrderSummaryFacts.toPromptText()` dùng giờ Việt Nam `HH:mm dd/MM/yyyy` kèm dòng `timezone`. API vẫn trả ISO/UTC. Bộ kiểm tra tóm tắt chỉ so **các con số rời** nên không phân biệt được UTC với giờ địa phương; cải thiện là do không còn đưa UTC cho model, không phải do bộ kiểm tra.
- **7.5, Gemini chép câu khuyến nghị thay vì mã:** mỗi khối căn cứ gửi cho model có trường `recommendation` (câu văn); model chép vào `recommendations` nên `OperationsNarrative.ids()` loại cả phản hồi. Tái hiện bằng gọi trực tiếp Gemini với prompt dựng lại (không phải bản sao từng byte của yêu cầu backend). Đã sửa: khối căn cứ chỉ gửi `id, text, source`; chỉ dẫn nêu rõ `insights` và `recommendations` chỉ chứa mã. Bộ kiểm tra **không** bị nới lỏng. Test MOCK trước đó không bắt được vì `MockAiProvider` luôn trả mã.
- Thêm 4 test backend mới và sửa 1 test (`OrderSummaryServiceTest`, `OperationsAnalyticsServiceTest`). Trong lúc sửa, test bắt được một chỗ sửa sót (ba dòng thời gian ở đầu prompt chưa đổi) và một khẳng định test quá mức; cả hai đã sửa.
- Khi viết test chat Flutter phát hiện lỗi giao diện thật: `ExpansionTile` trong khung có màu nền gây assertion ở debug. Đã sửa (đổi sang `Material`).
- Nhãn lý do dự phòng hiển thị hai dấu chấm ở web; đã sửa.

## 5. Phát hiện chưa xử lý (cần nhóm)

- **Model mặc định lỗi thời (7.1):** `application.yml` đặt `gemini-2.5-flash-lite`; Google trả 404 "no longer available to new users" cho khóa mới. Cần cập nhật mặc định (đã thử `gemini-3.5-flash-lite`, `gemini-3.1-flash-lite`, `gemini-flash-lite-latest` trả 200). Trong lúc thử, `gemini-3.5-flash`/`gemini-3.8-flash` trả 503/403 chập chờn.
- **Chat chập chờn (7.4):** trong log lần thử, 2/6 lượt chat với Gemini ra `AI_RESPONSE_INVALID` (4/6 `OK`); chưa điều tra nguyên nhân.
- **Độ trễ khi provider lỗi:** thử 3 lần có thể mất khoảng 22 giây trước khi ra bản dự phòng (trong giới hạn 30 giây của client).
- `application-sqlserver.yml` ghi mật khẩu `sa` trong file được commit; nên chuyển sang biến môi trường.
- `npm ci` báo 4 lỗ hổng dependency (3 moderate, 1 high) có sẵn.

## 6. Thay đổi ở file có sẵn

- `web`: `OrderDetailPage.tsx` (thêm 2 panel), `orders.css`, `AppRouter.tsx` (route `/assistant`, `/ai-analytics`), `navigation.ts` (bỏ `/assistant` và `/ai-analytics` khỏi trang tạm), `ReportsPage.tsx` (một link).
- `mobile`: `order_detail_screen.dart` (thêm 2 panel), `assistant_screen.dart` (thay màn giữ chỗ, bỏ nhãn "Demo" và dòng "sắp có").
- `mobile/test/order_detail_test.dart`: panel AI đẩy dòng "2 ảnh" ra ngoài vùng nhìn thấy nên test cũ lỗi; đã thêm cuộn tới dòng đó và gắn API AI giả. Không đổi logic kiểm tra.
- `backend` (commit riêng, cần người phụ trách 7.3 và 7.5 review): `OrderSummaryFacts.java`, `OperationsAnalyticsService.java`, `PromptBuilder.java` và hai file test.
- `docs/05-api/ai-api.md`, `docs/05-api/operations-ai-analytics.md` (ghi hai sửa đổi trên).
- `mobile/pubspec.lock` có thay đổi cũ chưa commit (gói `listen` bị bỏ, `test_api` hạ phiên bản, có vẻ do Flutter SDK cục bộ). **Không thuộc 7.6, không đưa vào commit.**
