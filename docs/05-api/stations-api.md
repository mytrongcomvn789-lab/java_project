# API trạm hạ cánh — Task 3.2

## Mô hình dữ liệu

DTO `Station` gồm `id`, `name`, `latitude`, `longitude`, `capacity`, `status`.
`capacity` là số drone tối đa có thể đỗ đồng thời, không phải số lượt theo khung giờ.
Trạng thái hiện hỗ trợ `ACTIVE` và `INACTIVE`; trạm ngừng hoạt động vẫn được giữ lại để
các đơn cũ tiếp tục tham chiếu bằng `stationId`. API không có thao tác xóa trạm.

Tên trạm được trim, không rỗng và tối đa 200 ký tự. Vĩ độ nằm trong `[-90, 90]`,
kinh độ trong `[-180, 180]`, sức chứa là số nguyên dương.

## API (Bearer JWT)

| Method | Endpoint | Quyền | Thành công |
|---|---|---|---|
| GET | `/api/v1/stations` | Cả 5 vai trò | `200`, danh sách trạm |
| POST | `/api/v1/stations` | `LOGISTICS_MANAGER` | `201`, Station + `Location` |
| PUT | `/api/v1/stations/{id}` | `LOGISTICS_MANAGER` | `200`, Station |
| PATCH | `/api/v1/stations/{id}/status` | `LOGISTICS_MANAGER`; operator đang bị chặn | `200`, Station |

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

Repo hiện chưa có dữ liệu hoặc bảng phân công nhân viên vào trạm. Vì không thể xác minh
operator nào được giao trạm nào, server trả `403 FORBIDDEN` cho mọi yêu cầu operator đổi
trạng thái. Cần thống nhất mô hình phân công với Luân trước khi mở quyền theo trạm; không suy
diễn rằng operator được phép sửa tất cả trạm.

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
vai trò khác chỉ xem. Operator thấy thông báo rằng cập nhật bị khóa do chưa có phân công.
