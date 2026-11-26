# APDDMP-55 — 7.5 Phân tích vận hành bằng AI

Phụ trách: **Lê Công Nhã**. Ngày triển khai: 25/11/2026.
Nhánh: `feature/7.5-operations-ai-analytics`.
Nền: AI Service 7.1 và thống kê 5.1–5.3; giao diện thuộc 7.6.

## 1. API và quyền

`POST /api/v1/ai/analytics`, JWT **LOGISTICS_MANAGER**, Cache-Control: no-store.
SYSTEM_ADMIN không mặc định có quyền nghiệp vụ. API key tích hợp không thay JWT quản lý.

```json
{"from":"2026-11-01","to":"2026-11-02","dateBasis":"CREATED","stationId":null}
```

Các trường tùy chọn: mặc định 30 ngày gần nhất, CREATED, tất cả trạm được phân công.
Ngày gồm cả hai đầu, Asia/Ho_Chi_Minh; tối đa 366 ngày. Sai khoảng/ngày/enum/UUID/body => 400.
Không đăng nhập => 401; sai role => 403. Thiếu phân công, trạm không tồn tại hoặc ngoài quyền => dữ liệu rỗng cùng cách thống kê 5.1, không mở rộng quyền.
stationId chỉ thu hẹp phạm vi. Thu hồi phân công có tác dụng ở lần gọi tiếp theo.

## 2. Hợp đồng phản hồi

Giữ envelope AiResult: result, model, mock, degraded, source, usage, requestId, generatedAt, fallbackReason.

| Trường result | Nguồn/ý nghĩa |
|---|---|
| report.summary | DTO nguyên bản StatisticsService.summary, cùng API statistics/orders/summary |
| report.daily | DTO nguyên bản daily; đủ ngày 0 |
| report.stationLoad | DTO nguyên bản stations/load tại report.collectedAt; tải khung giờ hiện tại |
| report.collectedAt | Instant lấy trước các truy vấn; không phải thời điểm AI kết thúc |
| analysis.failureRate, failureSample | FAILED / (DELIVERED + FAILED + RETURNING + RETURNED); null nếu mẫu 0 |
| analysis.trend | Hai cửa sổ bằng số ngày; tổng đơn, chênh lệch, tỷ lệ tăng/giảm |
| analysis.warnings | Mã cảnh báo chất lượng/phạm vi dữ liệu |
| analysis.evidence | Toàn bộ nhận định có ID, text, source và recommendation do Java tạo |
| insightIds, insights | Thứ tự nhận định AI chọn; text được dựng từ evidence |
| recommendationIds, recommendations | ID và nội dung khuyến nghị được phép |
| rulesVersion | ops-rules-v1 |
| narrativeMode | AI_SELECTED_VERIFIED_EVIDENCE hoặc DETERMINISTIC_FALLBACK |
| readOnly | true; không có hành động thay đổi lịch/đơn/trạm |

Source=MOCK là fixture, không phải Gemini thật. Source=FALLBACK/degraded=true là báo cáo xác định.
AI không tạo các con số trong report/analysis.

## 3. Công thức và cách diễn giải

- successRate giữ nguyên 5.1: DELIVERED / deliveryOutcomes.
- failureRate chỉ đếm trạng thái FAILED; **không bằng 1 - successRate** khi có hoàn hàng.
- returned/returning giữ riêng trong báo cáo gốc; cancelled/rejected không nằm trong mẫu kết quả giao.
- Thời gian: DISPATCH cuối không sau CONFIRM tới CONFIRM, đơn vị giây. count là đơn đủ mốc; excludedOrders là DELIVERED thiếu mốc. Không tự dùng createdAt làm thời gian giao.
- CREATED chọn ngày tạo; COMPLETED chọn lịch sử trạng thái kết thúc theo 5.1. FAILED chưa phải trạng thái kết thúc trong COMPLETED.
- Các nhóm trạng thái là **trạng thái hiện tại của tập đơn**, không phải ảnh chụp trạng thái ở quá khứ.
- Xu hướng: với N ngày, so sánh floor(N/2) ngày đầu và cuối; N lẻ bỏ ngày giữa và trả omittedMiddleDay. delta=last-first, changeRate=delta/first; first=0 => null. Một ngày => trend=null.
- Đây là biến động lượng đơn giữa hai cửa sổ, không dự báo và không chứng minh nguyên nhân.
- Tải trạm dùng khung giờ chứa collectedAt, không phải tải lịch sử của from/to. Tổng booked là lượt chỗ ở trạm, có thể đếm một đơn tại hai trạm; không gọi là số đơn duy nhất.
- Truy vấn dùng transaction đọc với isolation mặc định: không cam kết snapshot bất biến giữa các truy vấn khi dữ liệu đang đổi. Đối chiếu cùng fixture hoặc thời điểm ít biến động.

## 4. Ngưỡng cảnh báo v1

| ID | Điều kiện chính xác | Ý nghĩa |
|---|---|---|
| HIGH_FAILURE | failureSample >= 20 và failureRate >= 0.20 | Cần rà soát, không phải kết luận bất thường thống kê |
| HIGH_LOAD | Có trạm utilization >= 0.80 | Tải đặt chỗ cao trong slot hiện tại; không tự dời lịch |
| MISSING_TIMESTAMPS | deliveryTime.excludedOrders > 0 | Thiếu lịch sử để tính thời gian giao |

Ngưỡng là quy tắc minh bạch của phiên bản này, **chưa phải SLA thương mại được nhóm duyệt**.
Utilization null (sức chứa không hợp lệ/0) không bị coi là 0 hoặc cảnh báo ngưỡng giả.

Warnings: CURRENT_STATUS_NOT_HISTORICAL_SNAPSHOT, STATION_LOAD_IS_CURRENT_SLOT_NOT_PERIOD_TOTAL,
READ_COMMITTED_NOT_FROZEN_SNAPSHOT, NO_ORDERS_IN_SCOPE, LOW_DELIVERY_SAMPLE,
NO_DELIVERY_DURATION_SAMPLE, MISSING_DELIVERY_TIMESTAMPS, NO_VISIBLE_STATIONS,
UNDEFINED_STATION_UTILIZATION, TREND_REQUIRES_TWO_DAYS, TREND_ZERO_BASELINE.

## 5. Chống bịa số liệu, fallback và giới hạn AI

AI chỉ **chọn/sắp xếp ID nhận định có căn cứ** từ những evidence do Java tạo.
Hợp đồng nội bộ provider vẫn có insights/recommendations là mảng chuỗi nhưng chuỗi phải là ID hiện có, ví dụ ORDER_VOLUME.
Phản hồi API bên ngoài là văn bản tiếng Việt do server render theo ID.
Khối `evidence_*` gửi cho model chỉ có `id`, `text`, `source` (không có câu khuyến nghị: server tự dựng khuyến nghị từ ID). Chỉ dẫn hệ thống nêu rõ cả `insights` và `recommendations` chỉ chứa ID. Cập nhật 26/11/2026 (7.6): thử với Gemini thật `gemini-3.5-flash-lite` thấy model chép câu khuyến nghị vào `recommendations` nên phản hồi bị loại 6/6 lần; đã sửa theo hai điểm trên, cần nghiệm thu lại với khóa thật.
Không nhận văn xuôi tùy ý hoặc quan hệ nhân quả từ provider. ID không thuộc báo cáo, insights rỗng hoặc sai schema => fallback.
Trường extra (action, total, summary...) bị bỏ; model không có tool sửa dữ liệu.

Đây là lựa chọn có chủ đích để kiểm chứng chặt: AI xếp ưu tiên, không viết báo cáo tự do.
Hệ thống không tuyên bố đã chứng minh chất lượng suy luận Gemini chỉ qua test mock.

AI_NOT_CONFIGURED / AI_TIMEOUT / AI_PROVIDER_UNAVAILABLE / AI_RESPONSE_INVALID => HTTP 200, số liệu vẫn đủ,
source=FALLBACK, degraded=true và fallbackReason. Áp dụng cho 7.5 kể cả khi fallback chung 7.1 tắt.
AI_RATE_LIMITED và AI_BUDGET_EXCEEDED vẫn trả lỗi 429; không che quyền, bộ lọc hay lỗi DB.
Không có đơn trong phạm vi => không gọi provider, reason=NO_ORDERS_IN_SCOPE; vẫn trả tải trạm nếu nhìn thấy.
Fallback trình bày toàn bộ evidence theo thứ tự xác định. Shared AiService giữ audit/rate limit/budget/circuit breaker.

Chỉ gửi phạm vi ngày và evidence tổng hợp có kích thước giới hạn đến provider; không gửi tên trạm,
ID khách, địa chỉ, số điện thoại, email, lịch sử đơn thô hay toàn bộ 366 dòng daily.

## 6. Bàn giao UI 7.6

1. Form ngày/dateBasis/stationId; gọi endpoint bằng JWT quản lý.
2. Render report/analysis như dữ liệu chuẩn; không tính lại khác công thức.
3. Hiển thị số mẫu, đơn bị loại khỏi thời gian, warnings và collectedAt.
4. Hiển thị tải trạm thành khu vực riêng “khung giờ hiện tại”.
5. Hiển thị nhãn MOCK/FALLBACK; không gắn nhãn AI thật cho các nguồn này.
6. Render insights/recommendations dưới dạng text thuần; không thực thi action hoặc tự tạo liên kết.
7. Không giấu toàn bộ evidence cảnh báo chỉ vì AI không chọn nó trong insights.
8. 401/403 yêu cầu kiểm tra đăng nhập/phân quyền; 429 thông báo hạn mức, không retry vô hạn.

## 7. Test thủ công trong Swagger

- Đăng nhập CUSTOMER: POST => 403.
- Đăng nhập quản lý có phân công: chọn khoảng có đơn, so report.summary với GET /statistics/orders/summary cùng bộ lọc.
- So report.daily với GET /statistics/orders/daily.
- So tải bằng GET /statistics/stations/load?at=<collectedAt>, giữ stationId giống request.
- Chọn trạm ngoài quyền và khoảng rỗng: không có đơn lộ ra, có cảnh báo.
- Đảo from/to hoặc chọn trên 366 ngày => 400.
- Trong môi trường local/test, không cấu hình provider hoặc dùng fixture timeout: vẫn có báo cáo, FALLBACK.
- Provider MOCK không nghiệm thu ngôn ngữ/năng lực Gemini thật.
- SQL Server thật, dữ liệu đại diện và Gemini thật cần nghiệm thu môi trường riêng; test H2 không thay thế.
