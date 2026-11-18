# API trạm hạ cánh — Task 3.2

## Mô hình dữ liệu

DTO `Station` gồm `id`, `name`, `latitude`, `longitude`, `capacity`, `status`.
`capacity` là số drone tối đa có thể đỗ đồng thời, không phải số lượt theo khung giờ.
Trạng thái vận hành gồm `ACTIVE` (nhận đơn mới), `MAINTENANCE` (bảo trì, không nhận đơn mới) và
`INACTIVE` (ngừng hoạt động). Chỉ `ACTIVE` mới nhận đơn mới; trạm ở trạng thái khác vẫn được giữ lại
để các đơn cũ tiếp tục tham chiếu bằng `stationId`. API không có thao tác xóa trạm.

Tên trạm được trim, không rỗng và tối đa 200 ký tự. Vĩ độ nằm trong `[-90, 90]`,
kinh độ trong `[-180, 180]`, sức chứa là số nguyên dương.

## API (Bearer JWT)

| Method | Endpoint | Quyền | Thành công |
|---|---|---|---|
| GET | `/api/v1/stations` | Cả 5 vai trò | `200`, danh sách trạm |
| POST | `/api/v1/stations` | `LOGISTICS_MANAGER` | `201`, Station + `Location` |
| PUT | `/api/v1/stations/{id}` | `LOGISTICS_MANAGER` | `200`, Station |
| PATCH | `/api/v1/stations/{id}/status` | `LOGISTICS_MANAGER` (mọi trạm); `STATION_OPERATOR` (chỉ trạm được phân công) | `200`, Station |

Body tạo/sửa:

```json
{
  "name": "Trạm trung tâm Quận 1",
  "latitude": 10.7769,
  "longitude": 106.7009,
  "capacity": 4
}
```

Body đổi trạng thái:

```json
{ "status": "INACTIVE" }
```

Lỗi validation trả `400 VALIDATION_ERROR` với thông tin lỗi theo trường. Không có API
availability: lịch và giữ chỗ theo khung giờ thuộc giai đoạn điều phối, không trả dữ liệu giả.

## Phạm vi quyền operator

Operator chỉ đổi được trạng thái trạm có dòng phân công trong `staff_station_assignments`
(cùng nguồn `StaffStationScope` với 3.7 và 4.1). Trạm ngoài phạm vi hoặc chưa có phân công trả
`403 FORBIDDEN`; trạm không tồn tại trả `404`. Quyền được kiểm tra ở server, không chỉ ẩn nút.

## Lịch sử đổi trạng thái (audit)

Mỗi lần trạng thái thật sự thay đổi, server ghi một dòng vào `station_status_events` trong cùng
transaction: trạm, trạng thái cũ và mới, người đổi, vai trò, thời điểm. Gửi lại đúng trạng thái
hiện tại không đổi gì và không ghi thêm. Script: `backend/scripts/4.6-station-maintenance.sql`
(thêm `MAINTENANCE` vào ràng buộc CHECK và tạo bảng audit; chạy một lần trên database có sẵn).

## SQL Server và dữ liệu mẫu

Mặc định backend dùng Microsoft SQL Server (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD` có thể
ghi đè). Docker Compose tạo database `smartdrone`. DDL có tại
`backend/scripts/3.2-stations.sql`; dữ liệu demo tùy chọn tại
`backend/scripts/3.2-station-sample-data.sql`.
Đặt `MSSQL_SA_PASSWORD` trong file `.env` trước khi chạy Compose; giá trị trong
`.env.example` chỉ là mật khẩu mẫu local, cần thay riêng.

Chạy backend không cần SQL Server:

```powershell
cd backend
mvn test
mvn spring-boot:run "-Dspring-boot.run.profiles=h2"
```

Web dùng menu “Danh sách trạm”. `LOGISTICS_MANAGER` có thể thêm/sửa/đổi trạng thái; các
vai trò khác chỉ xem. Giao diện đổi trạng thái cho operator sẽ bổ sung ở phần web của 4.6.
## Giám sát trạm (4.6)

`GET /api/v1/stations/monitoring`: chỉ đọc, dùng dữ liệu trạm của 3.2 và dữ liệu đơn của giai đoạn 3.

- **Quyền:** `LOGISTICS_MANAGER` xem mọi trạm; `DISPATCHER` và `STATION_OPERATOR` chỉ xem trạm được phân công (`staff_station_assignments`). Vai trò khác nhận `403`, chưa đăng nhập nhận `401`. Nhân viên chưa được giao trạm nào nhận danh sách rỗng.
- **Tham số tùy chọn:** `status` = `ACTIVE` | `MAINTENANCE` | `INACTIVE` để lọc theo trạng thái vận hành.
- **Sắp xếp:** theo tên trạm, rồi theo id.

Ví dụ một phần tử trả về:

```json
{
  "id": "…",
  "name": "Trạm Quận 1",
  "status": "ACTIVE",
  "capacity": 20,
  "connection": { "state": "UNKNOWN", "lastSeenAt": null, "source": "NONE" },
  "packages": { "waitingDispatch": 2, "inbound": 1, "waitingPickup": 1, "processingTotal": 4 }
}
```

### Quy tắc đếm số kiện đang xử lý

Đếm theo kiện (`order_packages`), không đếm theo đơn. Quy tắc nằm ở `StationLoadRules`:

| Trường | Điều kiện |
|---|---|
| `waitingDispatch` | trạm gửi là trạm này, đơn `SCHEDULED` hoặc `PREPARING` |
| `inbound` | trạm nhận là trạm này, đơn `IN_TRANSIT` |
| `waitingPickup` | trạm nhận là trạm này, đơn `AWAITING_PICKUP` |
| `processingTotal` | tổng ba nhóm trên |

Các trạng thái khác (`PENDING`, `APPROVED`, `DELIVERED`, ...) không được tính.

### Kết nối (online/offline)

Hệ thống chưa có nguồn tín hiệu kết nối của trạm, nên `connection.state` luôn là `UNKNOWN`,
`lastSeenAt` là `null`, `source` là `NONE`. Giao diện hiển thị "chưa có dữ liệu", không giả trạng thái online.
Trạng thái vận hành (`status`) độc lập với kết nối.

### Sức chứa

`capacity` lấy từ thông tin trạm (3.2). API này không tính đặt chỗ hay slot theo khung giờ, phần đó thuộc 4.2.