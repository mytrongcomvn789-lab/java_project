# 6.5 — Mobile vận hành: xử lý giao thất bại và đồng bộ khi mất mạng

Nhánh: `feature/6.5-mobile-offline`. Backend **không đổi**; mobile dùng thêm API của 4.8 (`complete-return`) và lại các API của 6.4. Mobile không tự quyết định nghiệp vụ: server vẫn kiểm tra quyền, trạng thái và phiên bản.

## Phạm vi

| Có làm | Không làm |
|---|---|
| Tab **Chờ nhận hoàn** và màn xác nhận đã nhận hàng hoàn (RETURNING → RETURNED) ở trạm gửi; hiện lý do các lần giao thất bại | Báo giao thất bại (FAIL) và bắt đầu hoàn hàng: API này dành cho điều phối viên, không có trên app operator |
| Hàng đợi offline cho 3 thao tác: nhận kiện, xác nhận giao, nhận hàng hoàn | Xếp hàng khi **ảnh xác nhận chưa tải lên được**: chưa có ảnh thì chưa xác nhận giao được |
| Tự thử lại có giãn cách, nút "Đồng bộ ngay", đồng bộ khi mở lại app | Thư viện bắt sự kiện mạng (`connectivity_plus`); không thêm phụ thuộc mới |

## Khi nào một thao tác được xếp hàng

Chỉ khi lỗi là **mất mạng** (chắc chắn chưa tới server) hoặc **hết thời gian chờ** (chưa rõ). Mọi lỗi khác (404, 409, 412, mã sai…) giữ cách xử lý của 6.4 và không vào hàng đợi. Nếu **không ghi được** vào kho lưu trữ thì màn hình báo lỗi như trước (đơn CHƯA được thực hiện, vẫn thử lại được); app không bao giờ báo "đã lưu" khi chưa lưu.

Khi đã xếp hàng, màn hình hiện rõ **"đơn CHƯA được ... trên hệ thống"** và không cho bấm lần hai (tránh gửi trùng).

## Cách gửi lại từng thao tác

| Thao tác | Gửi lại | Cách làm |
|---|---|---|
| Xác nhận giao | An toàn | Gửi **y nguyên** `version`, `photoFileId`, mã, ghi chú. Server trả lại kết quả cũ nếu lần trước đã xử lý (`Idempotent-Replayed`). |
| Nhận kiện | **Không replay được** | Mỗi lần gửi lại **tải đơn trước**: chỉ gửi nếu đơn còn `SCHEDULED` đúng phiên bản lúc xếp hàng. Nếu lịch sử cho thấy đã sang `PREPARING` sau phiên bản đó thì coi như xong. |
| Nhận hàng hoàn | **Không replay được** | Như nhận kiện, với `RETURNING` → `RETURNED`. |

Kết quả của mỗi lần đồng bộ:

| Kết quả | Khi nào | Hành vi |
|---|---|---|
| Xong | Server trả 2xx, hoặc đơn đã ở trạng thái đích | Bỏ khỏi hàng đợi, làm mới các danh sách |
| Giữ lại, thử sau | Mất mạng, hết thời gian chờ, 5xx, 503 kho ảnh, 429 thường, 401 | Tăng số lần thử, **dừng cả đợt** (giữ thứ tự), hẹn lại |
| Cần xem lại | 404, 409, 412, mã nhận hàng sai/hết hạn/khóa, đơn đã đổi phiên bản hoặc trạng thái | Giữ trong hàng đợi kèm lý do, **không tự thử lại**; người dùng chọn "Mở đơn" hoặc "Bỏ yêu cầu" |

Thời gian chờ giữa các lần thử: 15 giây, nhân đôi mỗi lần, tối đa 5 phút. Đồng bộ ngay khi: mở app, mở lại app từ nền, bấm "Đồng bộ ngay".

## Lưu trữ và bảo mật

- Hàng đợi lưu trong `flutter_secure_storage` (Keystore/Keychain), mỗi tài khoản một khóa `smartdrone.pending.v1.<userId>`. Người đăng nhập sau **không thấy** việc của người trước, và bản ghi mang `userId` khác bị bỏ.
- Yêu cầu xác nhận giao chứa **OTP hoặc mã QR**; vì vậy không bao giờ ghi vào bộ nhớ thường. `toString` của `PendingAction` che nội dung. Sau khi xếp hàng, mã bị xóa khỏi trạng thái màn hình.
- Mã nhận hàng chỉ sống **30 phút** từ lúc khách mở. Hàng đợi để lâu thì server trả 410 và việc chuyển sang "cần xem lại", kèm hướng dẫn nhờ khách mở mã mới. Đây là giới hạn của nghiệp vụ, không phải lỗi của app.
- Bản ghi hỏng hoặc thiếu trường bị bỏ qua thay vì làm sập app.

## Màn hình mới

| Route / thành phần | Nội dung |
|---|---|
| Tab "Chờ nhận hoàn (N)" ở `/operator` | Đơn RETURNING có **trạm gửi** thuộc phạm vi operator (lọc hiển thị; quyền thật do server, trạm khác trả 404) |
| `/operator/returns/:id` | Lý do các lần thất bại (mới nhất trước), ghi chú, tích kiểm tra, hộp xác nhận, nút "Đã nhận hàng hoàn" |
| `SyncBanner` (đầu màn chính) | Số việc đang chờ, nút "Đồng bộ ngay", và các việc cần xem lại với "Mở đơn" / "Bỏ yêu cầu" |

## API mới mobile gọi

| Chức năng | API | Ghi chú |
|---|---|---|
| Danh sách đơn hoàn về | `GET /v1/orders?status=RETURNING` | Phân trang, lọc theo trạm gửi ở app |
| Xác nhận nhận hàng hoàn | `POST /v1/orders/{id}/complete-return` | `If-Match`; body tùy chọn `{note}`; chỉ operator trạm gửi |

## Kiểm thử

```sh
cd mobile
flutter pub get
flutter analyze --no-pub --no-fatal-infos
flutter test --no-pub
```

`flutter test`: **235 test đều qua** (195 trước đó). File mới `operator_sync_test.dart` có 40 test: lưu và đọc hàng đợi, bản ghi hỏng, giãn cách thử lại, tách theo tài khoản, từng nhánh đồng bộ (mất mạng giữa chừng, đã được xử lý ở lần trước, đổi phiên bản, đổi trạng thái, mã hết hạn/khóa, 429), thứ tự hàng đợi, tab nhận hoàn, màn hình và dải đồng bộ. Bốn test cũ của 6.4 về "mất mạng thì hiện Gửi lại" được viết lại theo hành vi mới (xếp hàng rồi đồng bộ).

### Chưa kiểm chứng

- **Chưa chạy trên emulator hoặc điện thoại thật với backend thật**: mọi kiểm thử ở trên dùng API giả. Cần thử: bật chế độ máy bay, nhận kiện / xác nhận giao / nhận hàng hoàn, tắt chế độ máy bay và bấm "Đồng bộ ngay".
- Kho `flutter_secure_storage` chỉ được kiểm bằng kho trong bộ nhớ; chưa thử trên thiết bị (khôi phục sau khi app bị hệ điều hành đóng).
- Hẹn giờ tự thử lại chỉ chạy khi app đang mở; app bị đóng thì đồng bộ ở lần mở sau (không có đồng bộ nền).
- Tab "Chờ nhận hoàn" cần đơn RETURNING thật (điều phối viên báo FAIL và START_RETURN qua API 4.8).
