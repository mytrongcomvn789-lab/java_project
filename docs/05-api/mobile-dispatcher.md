# Mobile: điều phối viên (DISPATCHER)

Nhánh `feature/mobile-ui-staff`. Backend không đổi: mobile dùng lại API đã có cho web (xem [order-state-machine](../03-architecture) và các trang `DispatchRequests`, `DispatchSchedule`, `DispatchFailures` của web).
Quyền do backend quyết định (RBAC trả 403); app chỉ hiện nút theo trạng thái đơn.

## 1. Màn hình

| Màn | Route | Nội dung |
|---|---|---|
| Đơn cần xử lý | `/dispatcher` | Header tối (tên, 3 ô thống kê), 3 tab: **Chờ duyệt** (`PENDING`), **Chờ lịch** (`APPROVED`), **Thất bại** (`FAILED`); thanh điều hướng: Đơn, Trạm (bảng trạm và sức chứa), Hồ sơ |
| Chi tiết đơn | `/dispatcher/orders/:id` | Trạng thái, tóm tắt AI (chỉ chạy khi bấm), lộ trình, người nhận, gói hàng, lịch sử; thanh nút theo trạng thái |
| Chọn khung giờ | bảng dưới | Chọn ngày (hôm nay + 3 ngày), lưới khung 30 phút của **trạm gửi** với số chỗ còn lại; chỉ chọn được khung còn chỗ |
| Lý do / báo thất bại | bảng dưới | Từ chối, hoàn hàng: nhập lý do bắt buộc. Thất bại: chọn mã lý do cố định; "Lý do khác" bắt buộc ghi chú |

## 2. Nút theo trạng thái đơn

| Trạng thái | Nút | API |
|---|---|---|
| `PENDING` | Duyệt đơn / Từ chối | `POST /v1/orders/{id}/approve`, `.../reject` (`{reason}`) |
| `APPROVED` | Lên lịch giao | `POST /v1/orders/{id}/schedules` (`{slotStart}` UTC) |
| `FAILED` | Giao lại / Hoàn hàng | `.../schedules` (giao lại), `.../return` (`{reason}`) |
| `SCHEDULED`, `PREPARING`, `IN_TRANSIT`, `AWAITING_PICKUP` | Báo giao thất bại | `POST /v1/orders/{id}/fail` (`{reasonCode, note?}`) |
| Trạng thái khác | không có nút | |

Dữ liệu đọc: `GET /v1/orders?status=...` (phân trang 20), `GET /v1/orders/{id}`, `GET /v1/stations/{id}/availability?date=yyyy-MM-dd` (giờ Việt Nam), `GET /v1/stations/monitoring`.

## 3. Quy tắc xử lý

- Mọi thao tác gửi `If-Match: "<phiên bản đang xem>"`.
- **Bị từ chối vì đơn đã đổi (412, hoặc 409 sai trạng thái):** hiện "thao tác CHƯA được thực hiện", tải lại bản mới, người dùng quyết định lại.
- **409 khác (ví dụ khung giờ vừa đầy):** hiện lời nhắn của server, giữ nguyên đơn và tải lại.
- **Mất mạng / hết thời gian chờ:** hiện lỗi; hết thời gian chờ thì nói rõ chưa biết đã thực hiện chưa và tải lại trạng thái. Điều phối viên **không** có hàng đợi offline (khác nhân viên trạm) vì quyết định cần dữ liệu mới nhất.
- Chặn bấm đúp bằng cờ đang gửi; nút hiện vòng quay và bị tắt.
- Khung giờ hiển thị theo giờ Việt Nam (UTC+7) bất kể múi giờ máy. Khung đã qua hoặc ngoài thời gian đặt bị ẩn; khung đã đầy vẫn hiện (mờ) kèm "đã đầy".
- Danh sách và chi tiết gắn với id người đăng nhập: đăng xuất hoặc đổi tài khoản thì dữ liệu của người trước bị bỏ.
- Chưa có kết nối thời gian thực cho điều phối viên (kênh realtime của backend chỉ dành cho khách); làm mới bằng kéo xuống hoặc nút tải lại.

## 4. Kiểm thử

```powershell
cd mobile
flutter analyze --no-pub --no-fatal-infos
flutter test --no-pub
```

`test/dispatcher_test.dart` (27 ca): định dạng giờ Việt Nam; từng API gửi đúng đường dẫn, `If-Match` và nội dung; đọc khung giờ; định tuyến vai trò; trang chủ (tên, số đếm, nhóm rỗng, mở chi tiết); thanh nút cho 6 trạng thái; duyệt có xác nhận và đúng phiên bản; từ chối bắt buộc lý do; 412 báo CHƯA thực hiện; mất mạng giữ đơn; lên lịch chỉ chọn khung còn chỗ, ẩn khung đã qua; đổi ngày tải lại; thất bại "Lý do khác" bắt buộc ghi chú; hoàn hàng.

## 5. Kiểm chứng trên emulator (27/11/2026)

Emulator Android 37, backend thật (`sqlserver,demo`), tài khoản `dispatcher@demo.local`:

| Ca | Kết quả |
|---|---|
| Đăng nhập | Vào `/dispatcher`; thống kê 0 chờ duyệt, 2 chờ lịch, 1 thất bại khớp API |
| Mở đơn `APPROVED` → **Lên lịch giao** | Bảng khung giờ hiện đủ 18 khung còn chỗ của Trạm Demo A; chọn 16:00 → server `schedules -> 200`, trạng thái **Đã lên lịch**, banner xanh |
| **Báo giao thất bại** (Thời tiết xấu) | Server `fail -> 200`, trạng thái **Giao thất bại**, thanh nút đổi sang Hoàn hàng / Giao lại |

**Chưa kiểm chứng trên thiết bị:** Duyệt và Từ chối (dữ liệu demo hiện không còn đơn `PENDING`, khách chưa tạo được đơn mới vì tìm vị trí địa chỉ đang lỗi 503), Giao lại, Hoàn hàng, Trạm, nhánh 412 trên giao diện thật.

**Lưu ý dữ liệu demo:** tên "Điều phối viên Demo" trong DB bị lỗi mã hoá ký tự (hiện "Đi?u ph?i viên Demo"); đây là dữ liệu do seeder ghi, không phải lỗi của app.
