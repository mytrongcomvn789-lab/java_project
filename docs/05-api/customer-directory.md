# FR-03 — Danh sách khách hàng cho điều phối viên

Trang `/customers` dùng API mới chỉ dành cho `DISPATCHER`:

| Endpoint | Chức năng |
|---|---|
| `GET /v1/customers?search=&page=1&pageSize=10` | Danh sách phân trang, tìm theo họ tên, email, số điện thoại; chỉ trả tài khoản có vai trò `CUSTOMER` |
| `PATCH /v1/customers/{id}` | Cập nhật `fullName` và `phone` của khách hàng; `phone` có thể là chuỗi rỗng để xóa |

Response chỉ gồm `id`, `email`, `fullName`, `phone`, `enabled`, `createdAt`. Không trả mật khẩu hay token. Email, vai trò và trạng thái khóa không sửa qua endpoint này; thuộc tính thừa trong request cập nhật bị từ chối. Hệ thống ghi audit `CUSTOMER_PROFILE_UPDATED` kèm actor, không ghi giá trị liên hệ vào audit. Response đặt `Cache-Control: no-store`.

Giao diện gồm tìm kiếm, phân trang, xem trạng thái và hộp sửa thông tin. Không cung cấp nút tạo/xóa khách hàng; đăng ký và quản trị tài khoản dùng luồng hiện có. Khách hàng vẫn có thể sửa hồ sơ của chính mình qua `/v1/me`.
