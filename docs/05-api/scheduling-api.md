# 4.2 — Điều phối viên lên lịch giao hàng và kiểm tra sức chứa

Jira: [APDDMP-31](https://nnha90190-1791007728869.atlassian.net/browse/APDDMP-31).
Backend Java Spring Boot. Task này chưa có giao diện web/mobile; chỉ có API và tài liệu.

## Nghiệp vụ

- Chỉ DISPATCHER được lên lịch, và chỉ đơn trong phạm vi trạm của mình (cùng `OrderScopeResolver`/`OrderSpecs` với 4.1). Ngoài phạm vi: 404.
- Lên lịch: APPROVED → SCHEDULED. Giao lại sau thất bại: FAILED → SCHEDULED, dùng chính API này. Cả hai cạnh đều do `TransitionRules` của 3.5 quy định và đều bắt buộc có `attemptId`.
- Mỗi lần lên lịch tạo một dòng `delivery_attempts` mới (`attempt_no` tăng dần); id của dòng đó là `attemptId` ghi vào lịch sử đơn. Không sửa dòng cũ.
- Một lần lên lịch **giữ 1 chỗ ở trạm gửi và 1 chỗ ở trạm nhận** trong khung giờ. Nếu hai trạm trùng nhau chỉ giữ 1 chỗ.
- Sức chứa (`stations.capacity`) là số chỗ giữ đồng thời tại trạm trong một khung giờ. Lịch được nhận khi số lần giữ chỗ ACTIVE chồng lên khung giờ ở **mỗi** trạm nhỏ hơn sức chứa.
- Trạm phải ở trạng thái ACTIVE; ngược lại trả 409 `STATION_UNAVAILABLE`.
- Chỉ OrderTransitionService được đổi trạng thái đơn; service lên lịch không tự gán status.

```mermaid
stateDiagram-v2
    APPROVED --> SCHEDULED: DISPATCHER + slot còn chỗ + If-Match
    FAILED --> SCHEDULED: giao lại, lần thử mới
    SCHEDULED --> PREPARING: Trạm nhận kiện (4.5)
```

## Quy tắc khung giờ

Cấu hình trong `application.yml`, nhóm `app.schedule` (thiếu hoặc sai thì backend không khởi động):

| Khóa | Mặc định | Ý nghĩa |
|---|---|---|
| `slot-minutes` | 30 | Độ dài một slot, phải chia hết 1440 (hoạt động 24/24) |
| `min-lead-minutes` | 30 | Lịch phải bắt đầu sau hiện tại ít nhất ngần này phút |
| `max-days-ahead` | 7 | Đặt xa nhất bao nhiêu ngày |

- `slotStart` là thời điểm UTC (ISO-8601, ví dụ `2026-11-17T03:00:00Z`), phải trùng mốc đầu slot, không có phần lẻ giây.
- Mốc slot tính theo UTC. Với slot 15, 30 hoặc 60 phút thì trùng với giờ Việt Nam; không nên dùng 45 hay 90 phút.
- Giới hạn gần nhất và xa nhất là bao gồm hai đầu.

## Hợp đồng HTTP

Base URL local: `http://localhost:5000/api`. Tất cả API cần Bearer token.

| API | Quyền | Header | Thành công |
|---|---|---|---|
| POST /v1/orders/{id}/schedules | DISPATCHER | Authorization, If-Match | 200 OrderDetail mới, ETag mới |
| GET /v1/stations/{id}/availability?date=yyyy-MM-dd | DISPATCHER, STATION_OPERATOR, LOGISTICS_MANAGER | Authorization | 200 StationAvailabilityView |

### Lên lịch

```json
{"slotStart": "2026-11-17T03:00:00Z"}
```

Lấy `ETag` từ `GET /v1/orders/{id}`, gửi lại ở `If-Match`. Thành công trả `OrderDetail` có `status = SCHEDULED`, `version` tăng 1, lịch sử có dòng mới kèm `attemptId`. Response có `Cache-Control: no-store`.

### Xem chỗ còn trống

`date` theo giờ Việt Nam; bỏ trống là hôm nay. Kết quả gồm `stationId`, `stationName`, `stationStatus`, `capacity`, `slotMinutes`, `date`, `timeZone` và mảng `slots`. Mỗi slot có `slotStart`, `slotEnd`, `used`, `free`, `bookable`.

`bookable = true` khi trạm ACTIVE, `free > 0` và slot nằm trong khoảng cho phép đặt. Với slot 30 phút, một ngày có 48 slot; slot đầu của ngày 17/11 có `slotStart = 2026-11-16T17:00:00Z`.

Giới hạn: API này chưa lọc theo phạm vi trạm của người gọi, vai trò hợp lệ xem được mọi trạm.

## Mã lỗi

| HTTP | code | Ý nghĩa / cách UI xử lý |
|---|---|---|
| 400 | VALIDATION_ERROR | Thiếu `slotStart`, lệch mốc, quá gần, quá xa, JSON sai |
| 401 | AUTH_REQUIRED | Chưa đăng nhập hoặc phiên hết hiệu lực |
| 403 | FORBIDDEN | Không đúng vai trò |
| 404 | RESOURCE_NOT_FOUND | Đơn ngoài phạm vi trạm hoặc không tồn tại; trạm không tồn tại |
| 409 | INVALID_STATE_TRANSITION | Đơn không ở trạng thái cho phép lên lịch |
| 409 | SLOT_FULL | Khung giờ đã đủ chỗ ở trạm gửi hoặc trạm nhận; chọn slot khác |
| 409 | STATION_UNAVAILABLE | Trạm gửi hoặc nhận không hoạt động |
| 412 | VERSION_CONFLICT | Đơn vừa được cập nhật, hoặc có người vừa lên lịch cùng đơn; tải lại |
| 428 | PRECONDITION_REQUIRED | Thiếu If-Match |

Thứ tự kiểm tra: phạm vi (404) → `slotStart` (400) → phiên bản (412) → trạng thái (409) → trạm và sức chứa (409).

## Transaction và chống đặt vượt chỗ

```mermaid
sequenceDiagram
    actor D as Điều phối viên
    participant API as OrderScheduleController
    participant S as OrderScheduleService
    participant C as SlotCapacityService
    participant W as OrderTransitionService
    participant DB as Database
    D->>API: POST /schedules + If-Match + slotStart
    API->>S: actorId từ JWT
    S->>DB: Tìm đơn trong phạm vi trạm
    S->>S: Kiểm tra slot, phiên bản, trạng thái
    S->>DB: Trả chỗ lần thử ACTIVE cũ (nếu có)
    S->>C: Khóa trạm theo thứ tự id, đếm chỗ
    C->>DB: SELECT ... FOR UPDATE (PESSIMISTIC_WRITE)
    alt còn chỗ
        S->>DB: INSERT delivery_attempts
        S->>W: transition(SCHEDULE, attemptId)
        W->>DB: UPDATE đơn + INSERT lịch sử
        S->>DB: COMMIT
        API-->>D: 200 OrderDetail + ETag mới
    else đầy chỗ hoặc lỗi
        S->>DB: ROLLBACK (kể cả việc trả chỗ cũ)
        API-->>D: 409 SLOT_FULL / STATION_UNAVAILABLE / 4xx
    end
```

- Khóa dòng trạm luôn theo thứ tự `id` để hai điều phối viên đặt chéo trạm không khóa lẫn nhau.
- Việc đếm chỗ, ghi `delivery_attempts` và đổi trạng thái nằm trong cùng một transaction: thất bại thì không còn dòng giữ chỗ nào.
- Ràng buộc duy nhất `(order_id, attempt_no)` là lớp chặn cuối khi hai người lên lịch cùng một đơn.

## Bàn giao cho các task khác

- **4.8 (thất bại, giao lại):** gọi `AttemptReleaseService.releaseActive(orderId)` trong transaction đổi trạng thái để trả chỗ. Giao lại dùng lại `POST /schedules`; nếu chỗ cũ chưa được trả thì API tự trả trước khi đếm chỗ.
- **4.5 (nhận kiện):** trạng thái SCHEDULED và `attemptId` trong lịch sử là đầu vào.
- **Sự kiện realtime:** 4.2 chưa phát sự kiện sau commit như `OrderReviewedEvent` của 4.1. Khi 4.3 chốt DTO sự kiện sẽ bổ sung theo cùng mẫu.

## Dữ liệu

Bảng `delivery_attempts` do Hibernate `ddl-auto=update` tạo khi khởi động. Môi trường quản lý schema thủ công chạy `backend/scripts/4.2-delivery-attempts.sql` (có thể chạy lại nhiều lần).

## Dùng thử trên Swagger

1. Đăng nhập tài khoản DISPATCHER đã được cấp trạm (cách cấp: xem `order-review-api.md`).
2. Duyệt một đơn PENDING, hoặc dùng đơn đã APPROVED; lấy `ETag` từ `GET /v1/orders/{id}`.
3. Gọi `GET /v1/stations/{id}/availability?date=...` để chọn slot có `bookable = true`.
4. Gọi `POST /v1/orders/{id}/schedules` với `slotStart` đó.
5. Gọi lại availability: slot vừa đặt phải có `used` tăng 1, `free` giảm 1.
6. Đặt thêm đơn vào cùng slot khi `free = 0` để thấy 409 `SLOT_FULL`.

## Kết quả kiểm thử

Bộ `OrderScheduleIntegrationTest` (server Spring thật, JWT thật, H2 riêng): **17 lượt chạy, 0 lỗi, 0 bỏ qua** (ngày 17/11/2026). Gồm: lên lịch thành công và lịch sử, số chỗ đã đặt/còn trống theo từng slot, `SLOT_FULL` ở trạm gửi và trạm nhận, trạm không hoạt động, đầu vào sai (428/400/412), trạng thái và phạm vi (409/404), quyền của các vai trò, giao lại giải phóng chỗ cũ, trả chỗ một lần, rollback, và hai đơn tranh chỗ cuối (đúng một đơn thắng, lặp 3 lần).
`ScheduleSlotPolicyTest`: 10/10. `RbacIntegrationTest` vẫn đạt sau khi thêm quy tắc availability.

```powershell
mvn -B test "-DforkCount=0" "-Dtest=OrderScheduleIntegrationTest,ScheduleSlotPolicyTest"
```

## Chưa kiểm chứng

- Test chạy trên H2. **Chưa chạy trên SQL Server thật**: khóa dòng `PESSIMISTIC_WRITE` và hành vi tranh chấp cần kiểm trên database test riêng.
- Test chưa đọc `attemptId` trong lịch sử đơn.
- Chưa gọi tay qua Swagger với dữ liệu trên máy dev.
- Chưa có giao diện web chọn slot.
- API availability chưa lọc theo phạm vi trạm của người gọi.
- Chưa phát sự kiện realtime sau khi lên lịch (chờ DTO sự kiện của 4.3).