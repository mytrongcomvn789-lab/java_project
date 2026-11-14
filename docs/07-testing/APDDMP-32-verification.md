# APDDMP-32 — Kiểm tra CRUD trạm

## Kết quả

- `mvn test -Dtest=OrderIntegrationTest,StationIntegrationTest`: đạt 26 test, 0 lỗi; 2 test cạnh tranh đồng thời được skip vì cần database thật.
- `npm run build`: đạt (`tsc --noEmit` và Vite production build).
- `docker compose config`: cấu hình SQL Server/MinIO hợp lệ khi cung cấp các biến môi trường bắt buộc.

Backend integration tests dùng H2 `MODE=MSSQLServer`, không giả nhận đã kiểm tra trên SQL Server thật.
Một lượt `mvn test` toàn bộ bị JVM dừng do thiếu bộ nhớ native sau khi chạy các test trước đó; vì vậy
không kết luận lượt toàn bộ suite thành công. Không có container SQL Server hoặc trình duyệt UI chạy
trong lượt kiểm tra này.

## Các luồng được bao phủ

- Manager tạo, đọc, sửa và ngừng hoạt động trạm; trạm ngừng hoạt động vẫn còn trong database.
- Cả năm vai trò đăng nhập đọc danh sách được; khách và vai trò không phải manager không thể ghi.
- Operator bị từ chối đổi trạng thái khi chưa có mô hình phân công.
- Từ chối tên/tọa độ/sức chứa sai và xác nhận luồng tạo đơn chỉ nhận trạm đang hoạt động.

Hướng dẫn API và demo: [stations-api.md](../05-api/stations-api.md).
