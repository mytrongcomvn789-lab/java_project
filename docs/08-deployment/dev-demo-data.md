# Dữ liệu demo cho môi trường dev (SQL Server local)

Mỗi máy có DB riêng nên tài khoản, trạm và đơn mỗi máy một khác. Để cả nhóm có cùng dữ liệu, backend có
`DemoDataSeeder`: tự tạo tài khoản, trạm, phân công và đơn mẫu khi khởi động.

## Cách dùng
1. Chạy SQL Server + MinIO: `docker compose up -d` (cần file `.env`, xem `.env.example`).
2. Chạy backend **kèm profile `demo`**:
   ```
   cd backend
   $env:DB_PASSWORD = "<MSSQL_SA_PASSWORD>"
   mvn spring-boot:run "-Dspring-boot.run.profiles=sqlserver,demo"
   ```
3. Chạy web: `cd web && npm run dev` (cổng 5173, backend chỉ cho phép CORS từ cổng này).
4. Đăng nhập bằng tài khoản bên dưới.

## Tài khoản demo (mật khẩu chung: `Demo@12345`)
| Email | Vai trò | Tên hiển thị |
|---|---|---|
| customer@demo.local | Khách hàng | Nguyễn Văn An |
| dispatcher@demo.local | Điều phối viên | Trần Thị Bình |
| operator@demo.local | Nhân viên trạm | Lê Minh Châu |
| manager@demo.local | Quản lý hậu cần | Phạm Quốc Dũng |
| admin@demo.local | Quản trị viên | Hoàng Thu Hà |

Điều phối viên, nhân viên trạm và quản lý được gán vào cả 4 trạm: Trạm Bến Thành, Trạm Thủ Đức, Trạm Chợ Lớn, Trạm Bình Thạnh.
Khách hàng demo có 14 đơn ở đủ trạng thái (PENDING, APPROVED, REJECTED, SCHEDULED, PREPARING, IN_TRANSIT,
AWAITING_PICKUP, DELIVERED, FAILED, RETURNING). Người nhận là các tên người Việt (Nguyễn Thị Lan, Trần Văn Hùng, ...).

## Lưu ý

- **DB tạo bằng bản seeder cũ** (tên có chữ "Demo": "Khách hàng Demo", "Trạm Demo A", "[DEMO] Người nhận n"): khi khởi động lại, seeder tự đổi tên tài khoản và trạm sang tên mới (không tạo trạm trùng). Tên người nhận của **đơn đã tạo** không tự đổi; muốn đổi thì xoá đơn mẫu theo hướng dẫn bên dưới và khởi động lại.
- Tên tài khoản hiển thị "Đi?u ph?i viên" (dấu `?`) trên DB cũ là do dữ liệu được ghi lúc cột còn là `varchar`; seeder cũng tự sửa tên này.
- Chạy nhiều lần không bị trùng: cái gì có rồi thì bỏ qua, không ghi đè (trừ việc đổi tên mẫu cũ sang tên mới nêu trên).
- Không có profile `demo` thì seeder không chạy (mặc định tắt). **Không bật ở môi trường thật**: mật khẩu demo công khai.
- Muốn làm lại đơn mẫu: xóa các đơn của `customer@demo.local` (và lịch sử, kiện hàng, lượt giao đi kèm) rồi khởi động lại.
- Phân công trạm cho tài khoản khác (không phải demo) vẫn dùng `backend/scripts/5.2-assign-staff-to-stations.sql`.
- Các đơn PREPARING, IN_TRANSIT, AWAITING_PICKUP, FAILED, RETURNING được tạo thẳng trạng thái, không có
  dữ liệu phụ như mã nhận hàng, nên một số bước tiếp theo trên các đơn đó có thể không chạy được.
