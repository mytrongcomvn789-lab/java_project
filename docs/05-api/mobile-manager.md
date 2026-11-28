# Mobile: quản lý hậu cần (LOGISTICS_MANAGER)

Nhánh `feature/mobile-ui-staff`. Backend không đổi. Chỉ đọc: quản lý xem số liệu và nhận định, không sửa đơn hay cấu hình. Xuất Excel/PDF dùng bản web. Quyền do backend quyết định (403 nếu sai vai trò).

## Màn hình (route `/manager`, thanh điều hướng 4 mục)

| Mục | Nội dung | API |
|---|---|---|
| Tổng quan | Chọn kỳ 7 hoặc 30 ngày; 4 chỉ số (tổng đơn, đang xử lý, đã giao, giao thành công); phân bố theo trạng thái; chỉ số giao hàng; biểu đồ cột đơn theo ngày vẽ bằng code; **Nhận định vận hành** | `GET /v1/statistics/orders/summary`, `/daily`, `POST /v1/ai/analytics` |
| Trạm | Tải của **khung giờ hiện tại** từng trạm (đã đặt/sức chứa, đang xử lý, mức sử dụng) | `GET /v1/statistics/stations/load` |
| Nhật ký | Sự kiện kiểm toán mới nhất trước, phân trang 20, lọc "Tất cả/Đăng nhập lỗi/Yêu cầu AI", nhãn tiếng Việt | `GET /v1/audit-events` |
| Hồ sơ | Dùng chung | `/v1/me` |

## Quy tắc hiển thị
- Khoảng ngày tính theo giờ Việt Nam (UTC+7). Tỷ lệ là null (mẫu rỗng) thì hiện "—", không hiện 0%.
- **Nhận định chỉ chạy khi bấm** (có thể tốn hạn mức AI). Luôn kèm nhãn nguồn: AI chọn nhận định đã được hệ thống kiểm chứng, hoặc bản dự phòng theo quy tắc (không dùng AI). Đổi kỳ thì kết quả cũ bị bỏ. Cảnh báo về số liệu (`warnings`) được dịch sang tiếng Việt, mã lạ giữ nguyên.
- Dữ liệu gắn với id người đăng nhập: đăng xuất hoặc đổi tài khoản thì bỏ số liệu của người trước.

## Kiểm thử
`test/manager_test.dart` (18 ca): định dạng, model, đường dẫn và khoảng ngày giờ Việt Nam, định tuyến vai trò, các màn hình (số liệu, đổi kỳ, mẫu rỗng, lỗi và Thử lại, nhận định chỉ chạy khi bấm, dự phòng, đổi kỳ bỏ kết quả cũ, trạm, nhật ký, lọc).

Đã chạy trên emulator 27/11/2026 với backend thật (`manager@demo.local`): Tổng quan (17 đơn, 50% giao thành công, biểu đồ), nhận định (`POST /v1/ai/analytics 200`, nhãn "Giả lập (MOCK)" vì backend demo dùng AI giả), Trạm (4 trạm), Nhật ký.
