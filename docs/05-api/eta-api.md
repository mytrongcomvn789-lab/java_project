# ETA đơn hàng (task 7.2) — bản công thức

> **Đây là ước tính nghiệp vụ, không điều khiển drone và KHÔNG phải mô hình học máy.**
> **Hồi quy chưa nghiệm thu** (xem mục 5). Vận tốc và thời gian chờ trạm là **giả định demo chưa hiệu chuẩn**.

Hợp đồng JSON chi tiết nằm ở mục 3.1 của [ai-api.md](ai-api.md). Tài liệu này giải thích phương pháp, cấu hình, cách demo và điều kiện nâng cấp.

## 1. Phương pháp

```
ETA = mốc xuất phát + ceil( khoảng cách haversine (km) ÷ vận tốc (km/h) × 60 ) phút + thời gian chờ trạm (phút)
```

| Trạng thái đơn | Mốc xuất phát | Chờ trạm |
|---|---|---|
| SCHEDULED, PREPARING | `slotStart` của lần giao ACTIVE; chưa có lịch thì `now`; mốc quá khứ bị chặn về `now` | có |
| IN_TRANSIT | thời điểm cất cánh lấy từ `order_status_history` (dòng `IN_TRANSIT` mới nhất); không có thì `now` | không (đã qua) |
| Trạng thái khác | không tính: `applicable=false`, `reason=STATUS_NOT_APPLICABLE` | — |

Khoảng cách là **đường chim bay** giữa trạm gửi và trạm nhận (bán kính Trái Đất 6371,0088 km). Không tính địa hình, vùng cấm bay, gió hay đường bay thực.

Các trường hợp không đoán:
- Thiếu hoặc sai toạ độ trạm (null, NaN, ngoài miền, đúng 0/0): `computed=false`, `reason=COORDINATES_MISSING`.
- Vận tốc hoặc thời gian chờ không hợp lệ: `SPEED_INVALID` / `WAIT_INVALID` (cấu hình sai còn bị chặn từ lúc khởi động).
- IN_TRANSIT quá hạn ước tính: `etaAt = now`, `etaMinutes = 0`, kèm giới hạn `OVERDUE_NO_LIVE_POSITION`. Chưa có GPS drone thật, nên không biết drone đang ở đâu.
- ETA không bao giờ nằm trong quá khứ.

`confidence` luôn là `LOW`. Đó không phải xác suất, chỉ báo tham số chưa hiệu chuẩn.

## 2. Cấu hình (`application.yml`, khối `app.eta`)

| Khoá | Giá trị hiện tại | Ghi chú |
|---|---|---|
| `speed-kmh` | 40 | Giả định demo. Bắt buộc, trong (0, 200]. Thiếu hoặc sai thì app không khởi động |
| `station-wait-minutes` | 5 | Giả định demo. Hằng số cho mọi trạm, chưa tính theo tải trạm. Bắt buộc, 0..240 |

Cần thông số thật của drone hoặc dữ liệu vận hành để hiệu chuẩn hai giá trị này.

## 3. Quyền

Giữ nguyên bảng quyền của 7.1 (`GET /v1/ai/orders/{id}/eta`):

| Vai trò | Kết quả |
|---|---|
| CUSTOMER | đơn của mình |
| DISPATCHER, STATION_OPERATOR | đơn thuộc trạm được phân công |
| LOGISTICS_MANAGER, SYSTEM_ADMIN | 403 |
| Không có JWT hoặc chỉ có API key | 401 |
| Đơn ngoài phạm vi hoặc không tồn tại | 404 `RESOURCE_NOT_FOUND` |

Phạm vi xem đơn do `OrderTrackingService` quyết định, cùng quy tắc với theo dõi đơn 4.4. Endpoint **không gọi Gemini/Mock**, không tốn token, không phụ thuộc khoá AI; kết quả luôn có `source = FORMULA`.

## 4. Hướng dẫn demo

1. Bật SQL Server local và chạy backend kèm dữ liệu demo (xem [dev-demo-data.md](../08-deployment/dev-demo-data.md)):
   `mvn spring-boot:run "-Dspring-boot.run.profiles=sqlserver,demo"`
2. Đăng nhập `POST /api/v1/auth/login` bằng `customer@demo.local` / `Demo@12345` để lấy JWT. Lấy `id` một đơn trạng thái SCHEDULED, PREPARING hoặc IN_TRANSIT qua `GET /api/v1/orders`.
3. Gọi `GET /api/v1/ai/orders/{id}/eta` với `Authorization: Bearer <token>`.
4. Kỳ vọng:
   - `source = "FORMULA"`, `result.method = "FORMULA_V1"`, `result.confidence = "LOW"`.
   - `etaAt` bằng `slotStart + travelMinutes + waitMinutes` (đơn SCHEDULED/PREPARING).
   - Với đơn DELIVERED hoặc PENDING: `applicable=false`.
5. **Bằng chứng đã chạy (25/11/2026, SQL Server local, profile `sqlserver`, tài khoản `customer@demo.local`):**

   | Trạng thái đơn | Kết quả |
   |---|---|
   | SCHEDULED | 200, `FORMULA`, ETA 22 phút (di chuyển 17 + chờ 5, 11,26 km) |
   | PREPARING | 200, `FORMULA`, ETA 29 phút (24 + 5, 15,62 km) |
   | IN_TRANSIT | 200, `FORMULA`, ETA 8 phút (8 + 0, 4,72 km) |
   | APPROVED, REJECTED, AWAITING_PICKUP, FAILED, RETURNING, DELIVERED | 200, `applicable=false`, `STATUS_NOT_APPLICABLE` |
   | Đơn không tồn tại | 404 |
   | `manager@demo.local` | 403 |
   | Không có JWT | 401 |

   Đây là kiểm tra chức năng bằng gọi tay trên dữ liệu seed. Nó **không** phải đánh giá độ chính xác của ETA.

6. Lưu ý với dữ liệu seed: các đơn IN_TRANSIT trong seed **không có dòng lịch sử cất cánh**, nên mốc xuất phát lùi về `now`. Số này chỉ để minh hoạ, không phải thời gian bay thật.

## 5. Đánh giá baseline và điều kiện nâng cấp hồi quy

**Kết quả đánh giá (25/11/2026): chưa lập được bảng sai số.** Tôi đọc DB local bằng SELECT: `order_status_history` chỉ có các bước tới `SCHEDULED` (PENDING 34, APPROVED 24, SCHEDULED 13, REJECTED 2, CANCELLED 2) và **không có dòng nào tới `IN_TRANSIT`, `AWAITING_PICKUP` hay `DELIVERED`**. Seed demo và `dev-sample-orders.sql` gán trạng thái trực tiếp. Vì vậy số mẫu có thời gian bay thật là **0**.

Hệ quả:
- Chưa chứng minh được 40 km/h và 5 phút sát thực tế.
- **Hồi quy chưa nghiệm thu.** Không dùng nhãn ML/AI cho công thức này.
- Sai lệch ETA chưa dùng được làm chỉ số cho 7.5.

Điều kiện để xem xét nâng cấp hồi quy (đề xuất, nhóm cần duyệt):
- Có tối thiểu **200 đơn DELIVERED** với đủ cặp mốc `IN_TRANSIT` → `AWAITING_PICKUP` đo bằng luồng vận hành thật. Con số 200 là tiêu chí tự đề xuất.
- **Tách train/test theo thời gian**: huấn luyện trên đơn cũ, kiểm trên đơn mới hơn mọi mẫu train.
- Chỉ dùng đặc trưng biết được **tại thời điểm dự báo** (khoảng cách, giờ trong ngày, tải trạm lúc đó). Không dùng mốc thời gian xảy ra sau khi dự báo, tránh rò rỉ dữ liệu tương lai.
- Báo cáo sai số (MAE, phân vị 90) của hồi quy so với baseline công thức trên tập test. Chỉ nâng cấp nếu hồi quy tốt hơn ổn định.

Đầu việc thu dữ liệu: bảo đảm luồng DISPATCH và ARRIVE ghi dòng lịch sử (code hiện đi qua `OrderTransitionService`, **chưa kiểm chứng** là mọi đường đều ghi lịch sử). Việc này nằm ngoài 7.2.

## 6. Chạy test

```
cd backend
mvn -o test -Dtest="GeoDistanceTest,EtaCalculatorTest,EtaInFlightTest,EtaApiIntegrationTest"
mvn -o test
```
