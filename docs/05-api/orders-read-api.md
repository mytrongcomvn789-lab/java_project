# Danh sách đơn, tìm kiếm và chi tiết – Task 3.7

## Làm được gì

Người dùng xem được **danh sách đơn** (có tìm kiếm, lọc, chia trang) và **chi tiết một đơn**.
Mỗi người chỉ thấy đơn trong phạm vi của mình. Việc giới hạn này làm ngay trong câu truy vấn
cơ sở dữ liệu, không phải tải hết rồi mới lọc.

## Ai xem được gì

| Vai trò                         | Thấy gì                                                                     |
| ------------------------------- | --------------------------------------------------------------------------- |
| Khách hàng                      | Chỉ đơn do mình tạo                                                         |
| Nhân viên trạm                  | Đơn có trạm gửi hoặc trạm nhận thuộc các trạm được giao                     |
| Điều phối viên, Quản lý hậu cần | Đơn thuộc các trạm trong phạm vi của họ (dùng cùng cách với nhân viên trạm) |
| Quản trị viên                   | Không có quyền xem đơn (trả 403)                                            |

Đơn ngoài phạm vi hoặc không tồn tại đều trả **404**, nên không ai dò được đơn của người khác.

**Lưu ý:** hệ thống chưa có bảng phân công trạm. Hiện nhân viên, điều phối viên và quản lý
**chưa thấy đơn nào** cho đến khi có bản cài đặt thật của `StaffStationScope`
(đang dùng `TemporaryStaffStationScope` trả về rỗng). Đây là chủ ý để không lộ dữ liệu.

## API

### GET /api/v1/orders

| Tham số   | Ý nghĩa                                                                         | Ví dụ                  |
| --------- | ------------------------------------------------------------------------------- | ---------------------- |
| code      | Mã đơn (UUID đầy đủ)                                                            | `3f2c...`              |
| status    | Trạng thái                                                                      | `PENDING`              |
| from, to  | Khoảng ngày tạo, giờ UTC ISO-8601; `to` không bao gồm mốc cuối; tối đa 366 ngày | `2026-11-09T00:00:00Z` |
| stationId | Trạm gửi hoặc nhận                                                              | UUID                   |
| sort      | `createdAt`, `updatedAt` hoặc `status`, kèm `,asc`/`,desc`                      | `createdAt,desc`       |
| page      | Từ 1 (mặc định 1)                                                               | `1`                    |
| pageSize  | 1–100 (mặc định 20)                                                             | `20`                   |

Mặc định sắp xếp theo `createdAt` giảm dần, sau đó theo `id` để thứ tự luôn ổn định.
Trả về `{ items, page, pageSize, total }`; `total` chỉ đếm các đơn bạn được phép xem.
Mỗi phần tử gồm: id, trạng thái, nhãn trạng thái, hai trạm, tên và địa chỉ người nhận,
mô tả và cân nặng gói, ngày tạo, ngày cập nhật. **Danh sách không trả số điện thoại.**

### GET /api/v1/orders/{id}

Trả đơn đầy đủ: trạng thái, trạm, người nhận (tên, điện thoại, địa chỉ, tọa độ),
gói hàng (chỉ có `fileId` ảnh), và **lịch sử trạng thái**.
Có header `ETag` là phiên bản đơn. Lịch sử không có mã người thực hiện hay mã truy vết.

Đầu vào sai (page, pageSize, status, UUID, ngày, sort ngoài danh sách) trả **400 VALIDATION_ERROR**.

## Ảnh gói hàng

Web xin link xem qua `GET /api/v1/files/{id}/download-link` (task 3.3). Ai xem được đơn thì
xem được ảnh của đơn (`OrderFileAccessPolicy`). Link hết hạn thì web tự xin link mới.
Quản lý hậu cần chưa có quyền tải ảnh trong RBAC hiện tại.

## Giao diện web

- `/deliveries`: ô lọc (mã đơn, trạng thái, từ/đến ngày, trạm, sắp xếp), bảng kết quả,
  chuyển trang, các trạng thái đang tải / trống / lỗi (có nút thử lại).
- `/deliveries/:id`: chi tiết đơn, người nhận, gói hàng kèm ảnh, lịch sử trạng thái.

## Hiệu năng

- Một truy vấn lấy danh sách, một truy vấn đếm, một truy vấn lấy gói hàng cho cả trang (không N+1).
- Thêm chỉ mục `(pickup_station_id, created_at)` và `(dropoff_station_id, created_at)`;
  đã có sẵn chỉ mục theo khách và theo trạng thái.

## Việc chưa làm / cần nhóm chốt

1. Phạm vi của điều phối viên và quản lý hậu cần (theo tổ chức hay theo trạm).
2. Mô hình phân công trạm (Luân) để thay `TemporaryStaffStationScope`.
3. Tìm theo mã đơn hiện chỉ nhận UUID đầy đủ; nếu cần gõ một phần mã thì phải đổi cách lưu/lập chỉ mục.
4. Nhân viên trạm hiện thấy đơn cả khi trạm là nơi gửi lẫn nơi nhận.
5. Tìm theo tên hoặc số điện thoại người nhận chưa hỗ trợ.

## Cách kiểm thử

`mvn -f backend/pom.xml test` và `cd web && npm run build`.
Demo: đăng nhập khách → tạo đơn (3.6) → mở `/deliveries` → lọc theo trạng thái và ngày
→ mở chi tiết; đăng nhập khách khác → không thấy đơn đó.
