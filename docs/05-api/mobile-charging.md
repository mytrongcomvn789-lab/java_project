# Mobile: thiết bị sạc, drone và điều phối cho nhân viên trạm

Nhánh `feature/mobile-ui-staff`. Dùng API do PR #65 (`feature/tram-sac-drone`) thêm; backend không đổi. Chỉ nhân viên trạm của đúng trạm (trạm khác trả 404).

## Màn hình
| Màn | Route | API |
|---|---|---|
| Trạm của tôi (mục "Trạm" ở thanh dưới) | `/operator/stations` | `GET /v1/stations/monitoring` (kết nối từ heartbeat bộ sạc, sức chứa khung giờ) |
| Chi tiết trạm: đếm trạng thái, lưới 8 thiết bị sạc, drone đang đỗ, giờ có chỗ nhận drone tiếp theo | `/operator/stations/:id` | `GET /v1/stations/{id}/chargers` |
| Chi tiết thiết bị (bảng): drone, pin, đã sạc/còn lại, nhiệt độ, tín hiệu | | cùng API trên |
| **Cho drone rời** (chỉ khi thiết bị đầy) | | `POST /v1/stations/{id}/chargers/{cid}/release` |
| **Bảo trì / hoạt động lại** (không khi còn drone) | | `PATCH .../maintenance` `{maintenance}` |
| Đội drone: đơn cần drone (giai đoạn, drone, thiết bị sạc đặt trước, giờ khởi hành/đến, thời gian chờ) và danh sách drone | `/operator/fleet` | `GET /v1/dispatch/board` |
| Phân công / tính lại drone (chỉ đơn **Đang chuẩn bị**) | | `POST /v1/orders/{id}/dispatch-plan` |

## Quy tắc hiển thị
- Số liệu sạc là **giả lập** (`source = SIMULATED`) và mốc điều phối là ước tính theo công thức: màn hình ghi rõ.
- Nút "Cho drone rời" bị tắt kèm lý do khi chưa đầy; bảo trì chỉ hiện khi thiết bị trống, còn drone thì có dòng hướng dẫn. Server vẫn là nơi quyết định: lời từ chối được hiện nguyên văn.
- Sau mỗi thao tác danh sách trạm và bảng điều phối được tải lại.
- Trạng thái lạ của backend không làm hỏng giao diện (hiện "Không rõ").

## Kiểm thử
`test/charging_test.dart` (16 ca): định dạng, màu đủ tương phản, model, đường dẫn API, danh sách trạm (không giả vờ online), chi tiết trạm, hết chỗ nhận drone, từng thao tác (rời, bảo trì, từ chối của server), lỗi tải và Thử lại, đội drone và quy tắc nút phân công.

Đã chạy trên emulator 28/11/2026 với backend `main` mới: danh sách 4 trạm Online, lưới 8 thiết bị (pin tăng theo thời gian), **cho drone rời A-03 (`release 200`, DR-A3 chuyển sang danh sách đỗ)**, bảng điều phối hiện kế hoạch thật. Phân công cho đơn chưa nhận kiện bị server từ chối (400), vì vậy app chỉ hiện nút với đơn Đang chuẩn bị.

**Chưa kiểm chứng trên thiết bị:** bật/tắt bảo trì, phân công thành công cho đơn Đang chuẩn bị (đã có test tự động).
