# Kiểm thử FR-03 — Khách hàng

Ngày 27/11/2026. Các thay đổi gồm `CustomerDirectoryService`, `CustomerDirectoryController`, quyền RBAC, giao diện `/customers` và tài liệu API.

| Ca | Kỳ vọng |
|---|---|
| Danh sách/tìm kiếm | Chỉ trả `CUSTOMER`, tìm đúng tên có dấu; phân trang sai trả 400 |
| Phân quyền | Không token 401; vai trò khác `DISPATCHER` 403 |
| Cập nhật | Họ tên/số điện thoại lưu được; email, quyền, trạng thái không đổi |
| Thuộc tính thừa | `email` trong PATCH trả 400 |
| Đối tượng khác vai trò | PATCH ID điều phối viên trả 404 |
| Web | TypeScript và Vite build production; trang danh sách, tìm kiếm, chỉnh sửa |

## Kết quả thực chạy

- `CustomerDirectoryIntegrationTest`: **2/2 đạt**, H2.
- `RbacIntegrationTest` (ma trận actor + anonymous): **282/282 đạt**.
- `npm run build`: TypeScript và Vite production **đạt**.
- Web thực tế: đăng nhập `dispatcher@demo.local` ở phiên demo riêng `127.0.0.1:5174`, thấy 1 khách hàng; sửa số điện thoại demo thành `0901234567` và thấy bảng cập nhật; tìm chuỗi không tồn tại hiện trạng thái rỗng, xóa tìm kiếm hiện lại danh sách.

Phiên demo riêng dùng backend H2 trên cổng 5001; không đụng vào dữ liệu của phiên trên cổng 5000. Chưa nghiệm thu SQL Server production.
