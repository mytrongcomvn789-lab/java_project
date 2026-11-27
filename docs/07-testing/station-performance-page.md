# Trang Hiệu suất hoạt động (FR-09)

Ngày 27/11/2026. Thay `/performance` từ trang giữ chỗ bằng màn hình so sánh tải các trạm, chỉ dành cho `LOGISTICS_MANAGER` như các API thống kê hiện có.

## Dữ liệu và cách tính

- `GET /v1/statistics/stations/load`: sức chứa, chỗ đã đặt, chỗ trống, kiện đang xử lý và tỷ lệ sử dụng của từng trạm trong **khung giờ hiện tại**. Các phép đếm và tỷ lệ của từng trạm do backend 5.1 tính.
- `GET /v1/stations/monitoring`: bổ sung địa chỉ trạm theo `stationId`. Không dùng trạng thái kết nối thiết bị để suy ra hiệu suất.
- Thẻ tổng quan cộng các trạm đang hiển thị sau khi lọc trạng thái. Tỷ lệ tổng = tổng chỗ đã đặt / tổng sức chứa; khi mẫu bằng 0 hiển thị “Chưa có dữ liệu”.
- Nút “Tải lại” lấy ảnh chụp khung giờ hiện tại mới. Bộ lọc trạng thái chỉ thay đổi phần hiển thị, không ghi dữ liệu.

## Đã kiểm tra

| Ca | Kết quả |
|---|---|
| `npm run build` (TypeScript và Vite production) | Đạt |
| Mở `/performance` bằng tài khoản quản lý demo | Hiện 4 trạm; tổng đã đặt 2/16, đang xử lý 2, sử dụng 12,5% tại thời điểm kiểm tra |
| Lọc “Bảo trì” khi không có trạm phù hợp | Hiện 0 trạm và thông báo rỗng; không chia cho 0 |
| Chuyển lại “Tất cả” | Bảng và 4 chỉ số trở lại |

Giới hạn: Đây là tải của **một khung giờ**, không phải năng suất theo ngày hay tình trạng kết nối drone. Cần dữ liệu telemetry/heartbeat riêng để đo phần cứng thật.
