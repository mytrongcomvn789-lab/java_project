# 5.1 — API thống kê đơn, tỷ lệ giao và tải trạm

Tài liệu này chốt **định nghĩa và công thức** dùng chung cho 5.2 (dashboard) và 5.3 (báo cáo). Dashboard và báo cáo gọi cùng một service; không nơi nào được tự tính lại số liệu ở frontend.

> Trạng thái: đã cài đặt và test trên H2 (xem mục 11). Chưa nghiệm thu bằng dữ liệu vận hành thật và chưa chạy trên SQL Server thật; việc đó chỉ làm được sau khi giai đoạn 4 (lịch, hoàn thành, thất bại) bàn giao dữ liệu.

## 1. Bộ lọc chung

| Tham số | Ý nghĩa | Mặc định / giới hạn |
|---|---|---|
| `from`, `to` | Ngày `yyyy-MM-dd`, **gồm cả hai đầu**, tính theo múi giờ `Asia/Ho_Chi_Minh` | 30 ngày gần nhất. Tối đa 366 ngày; `from` > `to` hoặc vượt giới hạn = `400 VALIDATION_ERROR` |
| `dateBasis` | `CREATED` (ngày tạo đơn) hoặc `COMPLETED` (ngày hoàn tất) | `CREATED` |
| `stationId` | Thu hẹp theo một trạm (trạm gửi **hoặc** trạm nhận) | Không có = toàn bộ phạm vi được phép. Trạm ngoài phạm vi = kết quả rỗng, không lộ dữ liệu |

- Múi giờ cố định `Asia/Ho_Chi_Minh` (tên chuẩn của `Asia/Saigon`, cùng giá trị đang dùng ở `StationAvailabilityService`), không nhận từ client. Khoảng ngày đổi sang Instant nửa mở `[from 00:00, to+1 00:00)`: đơn lúc 23:59:59 ngày `to` nằm trong, 00:00:00 ngày `to+1` nằm ngoài.
- `dateBasis=CREATED` lọc theo `orders.created_at`. `dateBasis=COMPLETED` lọc theo thời điểm hoàn tất ở mục 2; đơn chưa hoàn tất không có mặt trong chế độ này.
- Mọi mốc thời gian nghiệp vụ lấy từ `order_status_history.occurred_at` hoặc `delivery_attempts.created_at`. **Không dùng `orders.updated_at`** (đổi sau mỗi lần chuyển trạng thái).

## 2. Mốc hoàn tất của một đơn

| Trạng thái hiện tại | Mốc hoàn tất = `occurred_at` của dòng lịch sử |
|---|---|
| `DELIVERED` | `action = CONFIRM` |
| `RETURNED` | `action = COMPLETE_RETURN` |
| `CANCELLED` | `action = CANCEL` |
| `REJECTED` | `action = REJECT` |
| `FAILED`, `RETURNING` | Chưa hoàn tất (đơn `FAILED` vẫn có thể giao lại hoặc hoàn). Chỉ xuất hiện khi `dateBasis=CREATED` |

Mỗi đơn có tối đa một dòng cho mỗi trạng thái cuối (workflow 3.5 không cho đi tiếp), nên không đếm đôi.

## 3. Số đơn (đếm theo đơn, mỗi đơn đúng một lần)

Đơn được xếp theo **trạng thái hiện tại** vào đúng một nhóm:

| Nhóm | Trạng thái |
|---|---|
| `pending` | `PENDING` |
| `inProgress` | `APPROVED`, `SCHEDULED`, `PREPARING`, `IN_TRANSIT`, `AWAITING_PICKUP` |
| `delivered` | `DELIVERED` |
| `failed` | `FAILED` (giao thất bại, chưa quyết định giao lại hay hoàn) |
| `returning` | `RETURNING` |
| `returned` | `RETURNED` |
| `cancelled` | `CANCELLED` |
| `rejected` | `REJECTED` |

`total` = tổng các nhóm. Response cũng trả `byStatus` theo 12 giá trị `OrderStatus` gốc để UI tự gom nếu cần. Thống kê theo ngày (`daily`) chia nhóm theo **ngày địa phương** của mốc lọc.

## 4. Tỷ lệ

- **Tỷ lệ giao thành công** = `delivered / (delivered + failed + returning + returned)`.
  - Tử: đơn đã giao. Mẫu: đơn đã có kết quả giao hàng (giao được, hoặc đã thất bại/đang hoàn/đã hoàn).
  - Không tính vào mẫu: `pending`, `inProgress` (chưa có kết quả), `cancelled`, `rejected` (chưa từng được giao).
  - Đơn thất bại rồi giao lại thành công tính là `delivered`.
- **Tỷ lệ hoàn** = `(returning + returned) / mẫu` ở trên.
- **Tỷ lệ hủy** = `cancelled / total` (đơn bị từ chối `rejected` báo riêng, không gộp vào hủy).
- Mẫu bằng 0 (không có dữ liệu hoặc chia 0) → tỷ lệ là `null`, không phải 0. UI hiển thị "chưa có dữ liệu".
- Tỷ lệ trả dạng số thực trong `[0, 1]`; UI tự định dạng phần trăm.

## 5. Attempt (đếm riêng với đơn)

Nguồn: `delivery_attempts`, lọc theo `created_at` của attempt (hoặc theo mốc hoàn tất của đơn khi `dateBasis=COMPLETED`).

| Chỉ số | Công thức |
|---|---|
| `totalAttempts` | Số dòng attempt |
| `retryAttempts` | Số attempt có `attemptNo > 1` |
| `ordersWithRetry` | Số **đơn khác nhau** có ít nhất một attempt `attemptNo > 1` |
| `retryRate` | `ordersWithRetry / số đơn có ít nhất một attempt` (`null` nếu mẫu = 0) |
| `activeAttempts`, `releasedAttempts` | Theo `AttemptStatus` |

Số đơn và số attempt không bao giờ cộng chung: một đơn thử 3 lần là 1 đơn và 3 attempt.

## 6. Thời gian giao

- Mốc bắt đầu: dòng lịch sử `action = DISPATCH` (xuất phát). Mốc kết thúc: dòng `action = CONFIRM` (đã giao).
- Đơn giao lại: lấy `DISPATCH` **cuối cùng trước** `CONFIRM`.
- Chỉ tính đơn `DELIVERED` có đủ hai mốc và kết thúc ≥ bắt đầu; đơn thiếu mốc không bị đoán, được đếm ở `excludedOrders`.
- Trả `count`, `avgSeconds`, `minSeconds`, `maxSeconds` (giây); `null` nếu `count = 0`.

## 7. Tải trạm

Trả danh sách theo trạm trong phạm vi được phép; ba khái niệm tách riêng, không cộng lẫn:

| Trường | Ý nghĩa | Nguồn |
|---|---|---|
| `capacity` | Sức chứa tối đa (số drone đỗ đồng thời) | `Station.capacity` |
| `booked` | Số chỗ đã đặt trong khung giờ đang xét | `DeliveryAttemptRepository.countActiveOverlapping` (cùng nguồn kiểm tra sức chứa của 4.2 và giám sát 4.6) |
| `processing` | Số kiện đang xử lý tại trạm | `StationLoadRules` của 4.6: `PREPARING` ở trạm gửi + `AWAITING_PICKUP` ở trạm nhận |
| `utilization` | `booked / capacity`, `null` nếu `capacity = 0` | tính |

Khung giờ xét: tham số `at` (Instant, tùy chọn), mặc định là khung giờ hiện tại, cùng lưới slot với 4.2. `free = max(0, capacity - booked)`.

## 8. Quyền và phạm vi

- Chỉ `LOGISTICS_MANAGER`; vai trò khác `403`, chưa đăng nhập `401`. Kiểm ở server (`@PreAuthorize`), không chỉ ẩn nút.
- Phạm vi dữ liệu dùng lại **đúng** `OrderScopeResolver` / `StaffStationScope` của 3.7: đơn nằm trong phạm vi nếu trạm gửi hoặc trạm nhận thuộc các trạm quản lý được phân công; manager chưa có phân công thì kết quả rỗng (không phải toàn hệ thống). `stationId` chỉ thu hẹp thêm, không mở rộng.
- Tải trạm cũng lọc theo cùng tập trạm này.

> Lưu ý lệch với 4.6: màn giám sát trạm của 4.6 cho manager xem mọi trạm. Thống kê chọn phạm vi chặt hơn (theo phân công) để khớp phạm vi xem đơn; nếu nhóm muốn manager xem toàn hệ thống thì đổi ở một chỗ duy nhất (service chung).

## 9. Endpoint (tiền tố `/api`)

Cả ba: `GET`, chỉ `LOGISTICS_MANAGER`, header `Authorization: Bearer <accessToken>`, response có `Cache-Control: no-store`.

| URL | Tham số | Nội dung |
|---|---|---|
| `/v1/statistics/orders/summary` | `from`, `to`, `dateBasis`, `stationId` | Số đơn theo nhóm/trạng thái, tỷ lệ, attempt, thời gian giao |
| `/v1/statistics/orders/daily` | như trên | Chuỗi theo ngày (đủ mọi ngày trong khoảng, ngày không có đơn = 0) |
| `/v1/statistics/orders/export.xlsx` | `from`, `to`, `dateBasis`, `stationId`, `at` | Tải file `.xlsx`, gồm sheet thông tin, tổng hợp, theo ngày và tải trạm |
| `/v1/statistics/orders/export.pdf` | như trên | Tải file `.pdf` Unicode, nhiều trang, gồm tổng hợp, theo ngày và tải trạm |
| `/v1/statistics/stations/load` | `stationId`, `at` (Instant ISO-8601, tùy chọn) | Tải trạm theo mục 7; `at` được làm tròn xuống đầu khung giờ chứa nó |

### `GET /v1/statistics/orders/summary?from=2026-11-10&to=2026-11-10`

```json
{
  "from": "2026-11-10",
  "to": "2026-11-10",
  "timezone": "Asia/Ho_Chi_Minh",
  "dateBasis": "CREATED",
  "stationId": null,
  "orders": {
    "total": 8, "pending": 1, "inProgress": 1, "delivered": 3, "failed": 1,
    "returning": 1, "returned": 1, "cancelled": 0, "rejected": 0
  },
  "byStatus": {
    "PENDING": 1, "APPROVED": 0, "REJECTED": 0, "CANCELLED": 0, "SCHEDULED": 1, "PREPARING": 0,
    "IN_TRANSIT": 0, "AWAITING_PICKUP": 0, "DELIVERED": 3, "FAILED": 1, "RETURNING": 1, "RETURNED": 1
  },
  "rates": { "successRate": 0.5, "returnRate": 0.3333, "cancelRate": 0.0, "deliverySample": 6 },
  "attempts": {
    "totalAttempts": 6, "retryAttempts": 1, "ordersWithRetry": 1, "ordersWithAttempt": 5,
    "activeAttempts": 2, "releasedAttempts": 4, "retryRate": 0.2
  },
  "deliveryTime": { "count": 2, "excludedOrders": 1, "avgSeconds": 1500.0, "minSeconds": 1200, "maxSeconds": 1800 }
}
```

`byStatus` luôn đủ 12 trạng thái. Các tỷ lệ và `avgSeconds`/`minSeconds`/`maxSeconds`/`retryRate` là `null` khi không có mẫu.

### `GET /v1/statistics/orders/daily`

Mảng tăng dần theo ngày; `orders` cùng cấu trúc với `summary.orders`:

```json
[
  { "date": "2026-11-09", "orders": { "total": 1, "pending": 1, "inProgress": 0, "delivered": 0, "failed": 0, "returning": 0, "returned": 0, "cancelled": 0, "rejected": 0 } },
  { "date": "2026-11-10", "orders": { "total": 8, "...": "..." } }
]
```

Tổng `total` của mọi ngày bằng `summary.orders.total` của cùng bộ lọc.

### `GET /v1/statistics/stations/load`

```json
[
  {
    "stationId": "00000000-0000-0000-0000-000000000001", "name": "Trạm L1", "status": "ACTIVE", "capacity": 4,
    "slotStart": "2026-11-10T02:00:00Z", "slotEnd": "2026-11-10T02:30:00Z",
    "booked": 1, "free": 3, "processing": 2, "utilization": 0.25
  }
]
```

`free = max(0, capacity - booked)`. Sắp theo tên trạm.

### Lỗi

| Mã | Khi nào |
|---|---|
| `401` | Chưa đăng nhập hoặc token hết hạn |
| `403 FORBIDDEN` | Vai trò khác `LOGISTICS_MANAGER` (kể cả `SYSTEM_ADMIN`) |
| `400 VALIDATION_ERROR` | `from` > `to`; khoảng > 366 ngày |
| `400` | Sai định dạng: ngày không phải `yyyy-MM-dd`, `dateBasis` ngoài `CREATED`/`COMPLETED`, `stationId`/`at` sai kiểu |

Phạm vi trạm không bao giờ gây lỗi: ngoài phạm vi chỉ cho kết quả rỗng (`200`).

## 10. Quyết định triển khai cần nhóm biết

- **Nguồn thứ hai không có:** số đơn, attempt, tải trạm đều tái dùng `orders`, `order_status_history`, `delivery_attempts`, `StationLoadRules`, `countActiveOverlapping`; không có bảng tổng hợp riêng.
- **Phần tính ở Java:** chia theo ngày (`daily`) và thời gian giao lấy các dòng nhẹ (2 cột mỗi đơn) rồi tính ở service, vì phép đổi múi giờ/trừ thời điểm không có cách chung cho H2 và SQL Server. Khoảng bị chặn ở 366 ngày. Số đơn theo trạng thái, attempt và tải trạm do DB tổng hợp. Nếu dữ liệu rất lớn, chuyển hai phần này sang query native riêng SQL Server.
- **Chỉ mục:** thêm `ix_order_status_history_to_status_time` và `ix_delivery_attempt_created` (script `backend/scripts/5.1-statistics-indexes.sql`, idempotent). Thống kê theo ngày tạo tái dùng index của 3.7.
- **Đường dẫn tương lai:** `RbacPolicy` còn route giữ chỗ `GET /v1/analytics/deliveries` của nhóm khác, chưa có controller; không liên quan 5.1.

## 11. Kiểm thử

Chạy: `cd backend; mvn test -Dtest="Statistics*Test"` (hoặc cả bộ `mvn test`). Môi trường: JDK của dự án, H2 chế độ `MSSQLServer`.

| Test | Phủ |
|---|---|
| `StatisticsFormulasTest` (5) | Nhóm trạng thái không đếm đôi, mẫu tỷ lệ, chia 0 trả `null`, attempt tách khỏi đơn, thời gian giao |
| `StatisticsPeriodTest` (4) | Biên ngày UTC+7, mặc định 30 ngày theo giờ VN, giới hạn 366 ngày, `from > to` |
| `StatisticsIntegrationTest` (12) | Bộ dữ liệu pending/delivered/failed/retry/returned/cancelled; biên 23:59:59 và 00:00:00; hai mốc `CREATED`/`COMPLETED`; `daily` khớp `summary`; không dữ liệu; phạm vi trạm và `stationId`; 401/403; bộ lọc sai; tải trạm; 3000 đơn dưới 5 giây |

Toàn bộ backend: 654 test, 0 lỗi, 9 skip (bộ test có sẵn từ trước). **Dữ liệu trong test là fixture**, không thay cho demo dữ liệu vận hành thật.

## 12. Hướng dẫn demo

1. `cd backend; mvn spring-boot:run "-Dspring-boot.run.profiles=h2"` (không cần SQL Server; dữ liệu mất khi tắt).
2. Đăng nhập bằng tài khoản `LOGISTICS_MANAGER` (`POST /api/v1/auth/login`), lấy `accessToken`. Tài khoản này phải được phân công trạm trong `staff_station_assignments`; chưa phân công thì mọi thống kê rỗng.
3. Gọi ba endpoint ở mục 9 (xem `docs/05-api/statistics-demo.http`).
4. Dữ liệu demo thật cần đơn đã đi qua lịch, giao xong, thất bại của giai đoạn 4; nếu dùng dữ liệu mẫu thì ghi rõ trong ảnh/báo cáo là dữ liệu mẫu.

## 13. Việc còn lại sau 5.1

- Nghiệm thu bằng dữ liệu vận hành thật sau khi giai đoạn 4 bàn giao; xem execution plan trên SQL Server.
- 5.2 (dashboard) và 5.3 (báo cáo) chỉ gọi các endpoint này, không tự tính lại số liệu.

## 14. Dashboard web (5.2)

Trang `/dashboard` (chỉ `LOGISTICS_MANAGER`) gọi `summary`, `daily` và `stations/load` ở mục 9 với cùng một bộ lọc; web không tự tính lại số liệu, chỉ định dạng hiển thị. Tải trạm là **một khung giờ** (mặc định khung giờ hiện tại), không theo khoảng ngày đang lọc. Ngày mặc định và các nút chọn nhanh tính theo `Asia/Ho_Chi_Minh`. Kiểm chứng: `docs/07-testing/APDDMP-39-dashboard-verification.md`.
