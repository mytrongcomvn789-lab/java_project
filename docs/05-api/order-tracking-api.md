# 4.4 — Cập nhật tiến độ, timeline và bản đồ đơn giản (APDDMP-33)

## Phạm vi

Backend Java Spring Boot hiện có; web React dùng API chung, mobile (6.3) dùng cùng `GET tracking`. Không tạo nguồn trạng thái thứ hai: mọi thay đổi đi qua `OrderTransitionService` (3.5), timeline đọc lại `order_status_history`.

## API (tiền tố /api)

| Method và URL                 | Vai trò                                                   | Nội dung                                                                                                                      |
| ----------------------------- | --------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| GET /v1/orders/{id}/tracking  | CUSTOMER, DISPATCHER, STATION_OPERATOR, LOGISTICS_MANAGER | Trạng thái, timeline, hai trạm kèm toạ độ, vị trí suy ra, `allowedActions`. Cùng phạm vi xem đơn của 3.7; ngoài phạm vi = 404 |
| POST /v1/orders/{id}/dispatch | STATION_OPERATOR **ở trạm gửi**                           | PREPARING → IN_TRANSIT. Bắt buộc If-Match; body tùy chọn `{ "note": "≤500 ký tự" }`                                           |
| POST /v1/orders/{id}/arrive   | STATION_OPERATOR **ở trạm nhận**                          | IN_TRANSIT → AWAITING_PICKUP. Như trên                                                                                        |

- Đầu đi/đầu đến: dispatch lọc theo `pickupStationId`, arrive lọc theo `dropoffStationId`; sai đầu hoặc không được phân công = 404. Quyền được kiểm lại ở mỗi lần ghi.
- Cần đúng một DeliveryAttempt ACTIVE khớp hai trạm và khớp lần SCHEDULE gần nhất, nếu không 409.
- 401 chưa đăng nhập; 403 sai vai trò; 404 ngoài phạm vi; 400 body sai/trường lạ/ghi chú dài; 428 thiếu If-Match; 412 sai phiên bản; 409 sai trạng thái hoặc lần giao hết hiệu lực. Gửi lặp: If-Match cũ = 412, phiên bản mới nhưng sai trạng thái = 409; chỉ một dòng lịch sử và một sự kiện được tạo.
- Sau commit, phát `OrderReviewedEvent` bằng `OrderReviewEventPublisher` sẵn có (cùng nguồn với 4.1; `eventId` = id dòng lịch sử). Không tạo publisher thứ hai.

## Hợp đồng timeline

`timeline[]` tăng dần theo `orderVersion` (mỗi version đúng một dòng): `eventId, orderVersion, fromStatus, toStatus, toStatusLabel, action, actorRole, source, reason, attemptId, attemptNo, occurredAt`. Không có actorId. Khách không thấy ghi chú nội bộ (`reason` chỉ hiện với REJECT/FAIL). Client khử trùng/sắp lại theo `orderVersion`; có khoảng trống phiên bản thì tải lại REST.

## Bản đồ

Backend **chưa có GPS drone thật**: `map.liveGpsAvailable=false`. `pickup/dropoff` có `latitude/longitude` = null khi trạm không còn hoặc toạ độ không hợp lệ (UI báo thiếu toạ độ). `position.simulated=true` khi IN_TRANSIT: marker giữa tuyến chỉ là mô phỏng.

## Web

`OrderTrackingPanel` trong trang chi tiết đơn: sơ đồ SVG hai trạm, timeline, nút Xác nhận xuất phát / Đã đến trạm (theo `allowedActions`, server vẫn kiểm tra). **Hiện cập nhật bằng REST mỗi 10 giây và khi mạng trở lại; chưa nối realtime 4.3** (nhánh chưa có trên main). Mất kết nối hiện cảnh báo và giữ dữ liệu cũ.

## Giả định cần nhóm xác nhận

1. Dispatch do nhân viên **trạm gửi**, arrive do nhân viên **trạm nhận**.
2. Dùng lại `OrderReviewedEvent` cho DISPATCH/ARRIVE; 4.3 nên xác nhận hợp đồng này. Nếu nhánh 4.3 đã phát `OrderStatusChanged` trong `OrderTransitionService`, cần kiểm tra không trùng.
3. Trạng thái RECEIVE (4.5) hiện chưa phát sự kiện sau commit.

## Giải thích chức năng các file trong task

### Backend (`backend/src/main/java/com/smartdrone/`)

| File                                                   | Chức năng                                                                                                                                                                                                                                          |
| ------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `api/controller/OrderDeliveryProgressController.java`  | Hai endpoint `POST /v1/orders/{id}/dispatch` và `/arrive`. Đọc `If-Match`, body tùy chọn, gọi service, trả `OrderDetail` kèm ETag mới và `no-store`. Chỉ STATION_OPERATOR.                                                                         |
| `application/orders/OrderDeliveryProgressService.java` | Nghiệp vụ DISPATCH/ARRIVE: lọc đơn theo đúng đầu trạm (404 nếu sai), kiểm tra version, trạng thái, lần giao ACTIVE khớp lịch SCHEDULE gần nhất; gọi `OrderTransitionService` đổi trạng thái và ghi lịch sử; phát sự kiện sau commit.               |
| `application/orders/dto/DeliveryProgressRequest.java`  | Body của dispatch/arrive: chỉ có `note` (≤500 ký tự); trường lạ bị từ chối (actor và thời gian luôn do server quyết định).                                                                                                                         |
| `api/controller/OrderTrackingController.java`          | Endpoint `GET /v1/orders/{id}/tracking` cho 4 vai trò nghiệp vụ; trả ETag và `no-store`.                                                                                                                                                           |
| `application/orders/OrderTrackingService.java`         | Dựng dữ liệu theo dõi: kiểm phạm vi xem đơn (giống 3.7), đọc lịch sử 3.5 thành timeline sắp theo version, gắn số lần giao, ẩn ghi chú nội bộ với khách, lấy toạ độ hai trạm (null nếu thiếu/không hợp lệ), tính vị trí suy ra và `allowedActions`. |
| `application/orders/dto/TrackingView.java`             | Hợp đồng JSON của tracking: trạng thái, lần giao, hai trạm, `map` (không có GPS thật), `position` (có cờ `simulated`), `timeline`, `allowedActions`. Web và mobile 6.3 dùng chung.                                                                 |

### Test và cấu hình

| File                                                                                | Chức năng                                                                                                                               |
| ----------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `backend/src/test/java/com/smartdrone/api/orders/OrderTrackingIntegrationTest.java` | Test tích hợp HTTP thật trên H2: dựng đơn tới PREPARING bằng state machine rồi kiểm tra các nhóm ở mục A, kể cả bắt sự kiện sau commit. |
| `backend/src/test/resources/rbac-matrix.csv`                                        | Ma trận quyền độc lập: dispatch/arrive nay trả 428 (endpoint thật thay fixture 204), tracking trả 404 với id không tồn tại.             |

### Web (`web/src/`)

| File                                     | Chức năng                                                                                                                                                                                             |
| ---------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `lib/trackingService.ts`                 | Gọi API tracking/dispatch/arrive; định nghĩa kiểu dữ liệu; hàm `mergeTimeline` khử trùng và sắp lại timeline theo `orderVersion`, báo khi có khoảng trống phiên bản.                                  |
| `features/orders/OrderTrackingPanel.tsx` | Giao diện tiến độ: sơ đồ SVG hai trạm (đánh dấu mô phỏng), timeline, nút xuất phát/đã đến theo `allowedActions`, tự tải lại mỗi 10 giây và khi có mạng trở lại, cảnh báo mất kết nối và thiếu toạ độ. |
| `features/orders/OrderDetailPage.tsx`    | Trang chi tiết đơn có sẵn, được chèn thêm `OrderTrackingPanel`; khi version đổi thì tải lại đơn.                                                                                                      |

### Tài liệu

| File                                | Chức năng                                                                |
| ----------------------------------- | ------------------------------------------------------------------------ |
| `docs/05-api/order-tracking-api.md` | Chính file này: hợp đồng API, giả định, hướng dẫn test, giải thích file. |

## Kiểm chứng

- Web: `npm run build` (tsc + vite) **đạt**.
- Backend: test viết trong `OrderTrackingIntegrationTest`, cập nhật `rbac-matrix.csv`. **Chưa chạy**: môi trường soạn thảo không truy cập được Maven Central. Cần chạy `mvn test` và dán kết quả vào PR.
- Chưa có demo thật trên SQL Server, chưa test mobile (không thuộc phần đã làm), chưa test realtime.
