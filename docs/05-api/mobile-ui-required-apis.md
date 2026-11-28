# API cần cho giao diện mobile (đối chiếu với backend hiện có)

Cập nhật 27/11/2026, đối chiếu với `origin/main` sau PR 7.3. Dành cho thành viên làm backend: phần 2 là những gì
app đang chờ; mọi chỗ dùng dữ liệu mẫu trong app đều có nhãn **Demo** hoặc nút **Sắp có**, nên khi backend có đúng
JSON dưới đây thì chỉ cần bỏ phần dự phòng, giao diện không phải làm lại.

## 1. Đã có và app đang dùng

| Màn | API | Ghi chú |
|---|---|---|
| Đăng nhập, hồ sơ | `/v1/auth/*`, `/v1/me` | |
| Trang chủ, danh sách đơn | `GET /v1/orders`, `GET /v1/orders/{id}` | |
| Theo dõi đơn | `GET /v1/orders/{id}/tracking` | timeline, hai trạm kèm toạ độ, `position` (suy ra, `simulated`), `allowedActions` |
| Theo dõi đơn (realtime) | STOMP `/topic/orders/{id}`, `/topic/notifications/{userId}` | |
| ETA | `GET /v1/ai/orders/{id}/eta` | công thức 7.2, trang chủ và màn theo dõi |
| Tóm tắt đơn | `POST /v1/ai/orders/{id}/summary` | 7.3, chỉ chạy khi người dùng bấm |
| Mã nhận hàng | `GET /v1/orders/{id}/pickup-code` | OTP và `qrPayload`; app hiện OTP |
| Tạo, sửa, hủy đơn | `POST /v1/orders`, `PUT /v1/orders/{id}`, `POST /v1/orders/{id}/cancel` | `If-Match` theo phiên bản |
| Địa chỉ, trạm, ảnh | `/v1/addresses`, `GET /v1/stations`, `/v1/files/*` | |
| Thông báo | `GET /v1/notifications`, `POST .../{id}/read`, `POST .../read-all` | |
| Trợ lý AI | `POST /v1/ai/chat` | |
| Vận hành trạm | `station-receipts`, `receipts`, `confirmations`, `complete-return`, `stations/monitoring` | |

Lưu ý: RBAC khai báo `GET /v1/orders/{id}/eta`, `GET /v1/orders/{id}/summary` và `POST /v1/assistant/messages` nhưng
**không có controller** (404). App dùng các route `/v1/ai/**` thay cho chúng.

## 2. Còn thiếu

### 2.1 Số đo của drone — `GET /v1/orders/{id}/flight` (khách chủ đơn)
App hiện hiện **Demo** (tốc độ, độ cao, pin, tiến độ) khi tuyến trả 403/404/405/501. Cần thêm quyền trong `RbacPolicy`
(hiện bị mặc định từ chối nên trả 403). Hợp đồng:
```json
{ "progressPercent": 40, "droneId": "D-12",
  "telemetry": { "speedKmh": 52.0, "altitudeM": 120.0, "batteryPercent": 78 } }
```
Kèm STOMP `/topic/orders/{id}/telemetry` nếu muốn cập nhật liên tục (cần cho phép trong `StompSecurityInterceptor`).
Khi có GPS thật, `tracking.map.liveGpsAvailable=true` và `tracking.position` nên mang toạ độ để app vẽ drone thật.

### 2.2 Ước tính phí — `POST /v1/orders/estimate`
Body như `POST /v1/orders` (không ảnh). Trả `{ "feeVnd": 23000, "currency": "VND", "breakdown": [...] }`. App đang
tính phí **Demo** theo trọng lượng và không gửi giá lên server.

### 2.3 Loại kiện hàng — trường `packageType` trong `package`
Ví dụ `DOCUMENT|FOOD|FRAGILE|OTHER`. Chưa có trong `PackageInput`/`PackageView`; app chưa hiện ô chọn.

### 2.4 Hỗ trợ — `GET /v1/support/contact`
Số điện thoại/email/giờ làm việc. Nút "Hỗ trợ" ở màn theo dõi đang bị tắt kèm chữ "sắp có".

### 2.5 Khách yêu cầu giao lại / hoàn hàng
Khi đơn `FAILED` khách cần chọn **giao lại** hoặc **hoàn hàng**; hiện `/fail` và `/return` chỉ dành cho dispatcher.
Đề xuất `POST /v1/orders/{id}/delivery-requests` với `{ "type": "REDELIVER|RETURN" }` (dùng `If-Match`). Hai nút trong
màn Thông báo đang tắt kèm "sắp có". `tracking.attempts` và `maxAttempts` đã có để app hiện số lần thử.

### 2.6 Phân loại thông báo — trường `category` trong `NotificationView`
Ví dụ `DELIVERY|ISSUE|SYSTEM`. App đang phân loại **ở phía máy** theo `status` của đơn (`NotificationFilter`).

### 2.7 Trạm: heartbeat và thống kê trong ngày
Trang vận hành cần `lastSeenAt` (để hiện Online/Offline thật; app hiện không giả Online) và `today` (nhận/giao/thất bại
trong ngày) trong `GET /v1/stations/monitoring`.

### 2.8 Mã QR nhận hàng
Backend đã trả `qrPayload`, nhưng app **chưa vẽ** được QR vì chưa có thư viện tạo mã QR trong `pubspec.yaml`
(đề xuất `qr_flutter`). Hiện khách đọc OTP cho nhân viên; nhân viên vẫn nhập OTP hoặc quét QR của khách ở máy khác.

## 3. Tiêu chí nghiệm thu phía app
- Không có API: giao diện không lỗi, hiện Demo hoặc Sắp có, không giả dữ liệu thật.
- Có API đúng hợp đồng: không còn nhãn Demo (nhãn chỉ hiện khi dữ liệu là mẫu).
- Mất mạng, 401, 429 và lỗi máy chủ đều có thông báo tiếng Việt và nút Thử lại (`AsyncStateView`).

## 4. Bổ sung 28/11/2026

### Đã làm phía mobile
- Nhân viên trạm: **Xuất phát** (`POST /v1/orders/{id}/dispatch`) và **Drone đến** (`.../arrive`) ở tab "Đang giao", mở trọn vòng đời đơn trên điện thoại.
- Điều phối viên (`/dispatcher`) và quản lý hậu cần (`/manager`): xem [mobile-dispatcher.md](mobile-dispatcher.md), [mobile-manager.md](mobile-manager.md).
- Đổi mật khẩu (`POST /v1/me/password`, máy chủ thu hồi mọi phiên nên app đăng xuất), bằng chứng giao hàng cho khách (`GET /v1/orders/{id}/confirmation` và `/v1/files/{id}/download-link`), sửa đơn chờ duyệt (`PUT /v1/orders/{id}`, địa chỉ phải chọn lại vì đơn chỉ lưu bản chụp địa chỉ).

### Còn thiếu ở backend
| Thiếu | Ảnh hưởng |
|---|---|
| **API nhân viên trạm báo sự cố** (ví dụ `POST /v1/orders/{id}/incidents` kèm mã lý do, ghi chú) | Yêu cầu 6.5: nếu operator không có quyền FAIL thì báo sự cố để điều phối viên xử lý. Hiện `/fail` chỉ dành cho điều phối viên và chưa có route nào khác, nên mobile chưa làm được |
| Đăng ký tài khoản khách đã có (`/v1/auth/register`) nhưng mobile chưa có màn | Khách mới phải đăng ký trên web |
| Kênh realtime cho điều phối viên | Danh sách của điều phối viên phải kéo xuống để làm mới |
| Quên mật khẩu | Chưa có API |
