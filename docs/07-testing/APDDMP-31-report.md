# APDDMP-31 — Báo cáo thực hiện 4.2

Ngày: 17/11/2026 (Asia/Saigon). Nhánh: `feature/4.2-order-scheduling`, phát triển từ main `bdc3870` (đã gồm 4.1).
Jira: [APDDMP-31](https://nnha90190-1791007728869.atlassian.net/browse/APDDMP-31).

## Mục tiêu và đầu ra

Điều phối viên lên lịch giao hàng cho đơn APPROVED vào một khung giờ, hệ thống kiểm tra sức chứa ở trạm gửi và trạm nhận, giữ chỗ và chuyển đơn sang SCHEDULED trong cùng một transaction. Giao lại đơn FAILED dùng lại chính API này. Có API xem chỗ còn trống theo từng slot.

Chưa làm: giao diện web chọn slot, sự kiện realtime sau khi lên lịch (chờ 4.3), Flutter.

## Bảng công việc và file thay đổi

| Phần | Thêm/sửa | Mục đích |
|---|---|---|
| Cấu hình | application.yml, ScheduleProperties, ScheduleConfig | `app.schedule.*` (slot, đặt sớm nhất, đặt xa nhất); thiếu hoặc sai thì không khởi động |
| Dữ liệu | DeliveryAttempt, AttemptStatus, DeliveryAttemptRepository, scripts/4.2-delivery-attempts.sql | Mỗi lần lên lịch một dòng; id chính là `attemptId` mà luật SCHEDULE yêu cầu |
| Mã lỗi | ErrorCode | Thêm `SLOT_FULL`, `STATION_UNAVAILABLE` (409) |
| Luật slot | ScheduleSlotPolicy | Căn mốc, không quá gần, không quá xa; không đụng database |
| Sức chứa | SlotCapacityService, StationRepository | Khóa dòng trạm theo thứ tự id rồi đếm chỗ; bắt buộc chạy trong transaction |
| API lên lịch | OrderScheduleController, OrderScheduleService, ScheduleOrderRequest | `POST /v1/orders/{id}/schedules`; phạm vi trạm như 4.1; đổi trạng thái qua OrderTransitionService |
| API chỗ trống | StationAvailabilityController, StationAvailabilityService, StationAvailabilityView | `GET /v1/stations/{id}/availability?date=` |
| Trả chỗ | AttemptReleaseService | 4.8 gọi khi đơn thất bại |
| Phân quyền | RbacPolicy, rbac-matrix.csv | Thêm quy tắc availability; dòng schedules kỳ vọng 400 |
| Test | ScheduleSlotPolicyTest, OrderScheduleIntegrationTest | Xem bên dưới |
| Tài liệu | scheduling-api.md, rbac-api.md | Hợp đồng API; sửa bảng quyền |

Đường dẫn lên lịch là số nhiều `/schedules`, theo hợp đồng chung của nhóm (APDDMP-9) và ma trận RBAC.

## Kết quả kiểm thử thực thi

| Hạng mục | Kết quả |
|---|---|
| Toàn bộ Maven clean verify | **418 test; 0 failure, 0 error, 9 bỏ qua — BUILD SUCCESS** |
| OrderScheduleIntegrationTest (mới) | **17 lượt chạy, đạt hết** (12 phương thức; ca vai trò chạy 4 lần, ca tranh chấp chạy 3 lần) |
| ScheduleSlotPolicyTest (mới) | **10/10** |
| RbacIntegrationTest sau khi thêm quy tắc | **196/196** |

Môi trường: Windows, Java 21.0.13, Spring Boot 4.0.5, H2 2.4.240 (profile h2), Maven `-DforkCount=0`.
**9 test bị bỏ qua thuộc các phần trước** (MinIO và concurrency của OrderIntegrationTest cần database ngoài); không tính chúng là đạt.

## Các ca 4.2

| Ca | Kiểm tra | Kết quả |
|---|---|---|
| Lên lịch | 200, SCHEDULED, ETag tăng, dòng giữ chỗ đúng trạm/slot/người tạo, lịch sử có hành động SCHEDULE | Đạt |
| Chỗ trống | `used`/`free`/`bookable` đổi đúng sau khi đặt, ở trạm gửi và trạm nhận | Đạt |
| SLOT_FULL | Bị chặn khi đầy ở một trong hai trạm; không để lại dòng giữ chỗ; slot kế tiếp vẫn đặt được | Đạt |
| Trạm không hoạt động | 409 STATION_UNAVAILABLE, không giữ chỗ | Đạt |
| Đầu vào sai | 428, 400 (lệch mốc, quá gần, quá xa, thiếu body, JSON sai), 412 | Đạt |
| Trạng thái, phạm vi | PENDING và đã SCHEDULED bị 409; ngoài phạm vi và không tồn tại bị 404 | Đạt |
| Quyền ×4 | CUSTOMER, SYSTEM_ADMIN, STATION_OPERATOR, LOGISTICS_MANAGER bị 403; chưa đăng nhập bị 401 | 4 đạt |
| Availability | Quyền theo vai trò, 404 với trạm không tồn tại | Đạt |
| Giao lại | Đơn FAILED đặt lại đúng slot cũ khi trạm chỉ còn 1 chỗ: chỗ cũ tự được trả | Đạt |
| Trả chỗ | `releaseActive` trả đúng một lần; bắt buộc có transaction | Đạt |
| Rollback | Hủy transaction thì không còn dòng giữ chỗ, đơn vẫn APPROVED, lịch sử không đổi | Đạt |
| Tranh chấp ×3 | Hai đơn cùng tranh chỗ cuối: đúng một đơn 200, một đơn 409 SLOT_FULL; đúng 1 dòng ACTIVE | 3 đạt |

## Cách demo lại

1. Chạy backend, đăng nhập tài khoản DISPATCHER đã được cấp trạm (cách cấp: `order-review-api.md`).
2. Duyệt một đơn PENDING; lấy ETag từ `GET /v1/orders/{id}`.
3. `GET /v1/stations/{id}/availability?date=...` để chọn slot có `bookable = true`.
4. `POST /v1/orders/{id}/schedules` với `slotStart` đó và `If-Match`.
5. Gọi lại availability: slot đó phải có `used` tăng 1.
6. Đặt thêm đơn vào cùng slot khi `free = 0` để thấy 409 SLOT_FULL.

## Giới hạn cần biết khi review

- Test chạy trên **H2**. Chưa chạy trên SQL Server thật: khóa dòng `PESSIMISTIC_WRITE` và hành vi tranh chấp cần kiểm trên database test riêng.
- Chưa gọi tay qua Swagger với dữ liệu dev; kết quả trên đến từ test tự động.
- Script `4.2-delivery-attempts.sql` chưa được chạy; bảng được Hibernate tạo ở môi trường dev.
- Test chưa kiểm `attemptId` trong lịch sử đơn.
- API availability chưa lọc theo phạm vi trạm của người gọi.
- Chưa phát sự kiện realtime sau khi lên lịch; chờ DTO sự kiện của 4.3.
- Chưa có giao diện web chọn slot.
- Chờ review PR trước khi merge và chuyển Jira Done.