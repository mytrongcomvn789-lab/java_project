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

### Kiểm chứng trên emulator với backend thật

Môi trường: emulator Android 37, backend Spring Boot với SQL Server local (profile `sqlserver,demo`). Mất mạng bằng chế độ máy bay; kết quả phía server đọc lại bằng API.

| Kịch bản | Kết quả |
|---|---|
| **Nhận kiện** khi mất mạng | Màn hình ghi "Đơn CHƯA được nhận trên hệ thống", nút nhận biến mất. Server vẫn `SCHEDULED` (phiên bản 2). Màn chính hiện banner vàng "1 thao tác chờ đồng bộ khi có mạng". |
| Bật lại mạng, bấm **Thử lại** | Server chuyển `SCHEDULED → PREPARING` (phiên bản 3), đúng một lần; banner biến mất, số "Chờ nhận" giảm 3 → 2, đơn rời danh sách. |
| **Nhận hàng hoàn** khi mất mạng | Cùng hành vi xếp hàng như trên. Khi có mạng, server từ chối (xem lưu ý dữ liệu demo), thao tác chuyển sang "cần xem lại" kèm lý do của server và **không tự thử lại**; "Bỏ yêu cầu" xóa được. |
| Tắt hẳn app rồi mở lại | Thao tác trong hàng đợi (kể cả loại "cần xem lại") **còn nguyên** vì lưu trong kho mã hóa. |
| **Xác nhận giao** khi mất mạng (27/11/2026) | Đơn `C8791B24` ở `AWAITING_PICKUP`, ảnh đã tải lên, OTP đúng, bật chế độ máy bay rồi xác nhận: màn hình ghi "Đơn CHƯA được giao trên hệ thống; app sẽ tự gửi khi có mạng", có nhắc mã chỉ hiệu lực 30 phút. Server vẫn `AWAITING_PICKUP`, log không có `confirmations` thành công. Danh sách hiện banner "1 thao tác chờ đồng bộ khi có mạng". |
| Bật lại mạng (không thao tác gì thêm) | Sau khoảng 40 giây app **tự gửi**: server ghi `POST .../confirmations -> 200` **đúng 1 lần**, đơn thành `DELIVERED` (phiên bản 6, phương thức `OTP`); banner biến mất, "Chờ giao" giảm 3 → 2, đơn rời danh sách. |
| Nhập sai OTP khi đang có mạng | Server trả `422`; app báo "Mã nhận hàng không đúng. Còn 4 lần thử", xoá ô OTP, đơn vẫn `AWAITING_PICKUP`. |

**Lưu ý dữ liệu demo:** `DemoDataSeeder` tạo các đơn đã qua trạng thái PENDING với lịch sử tới phiên bản 2 nhưng đơn chỉ ở phiên bản 1, nên mọi chuyển trạng thái tiếp theo bị backend từ chối (`412`, log: `Violation of UNIQUE KEY constraint 'uq_order_status_history_version'`). Đó là lỗi của dữ liệu seed, không phải của mobile (gọi thẳng `complete-return` bằng `curl` cũng `412`). Để thử đầy đủ, dùng đơn PENDING của seeder rồi đưa qua quy trình thật: điều phối viên `approve` (`If-Match: "0"`) và `schedules`.

### Chưa kiểm chứng

- Nhận hàng hoàn thành công sau khi có mạng lại (bị chặn bởi lỗi dữ liệu seeder, xem lưu ý trên). Xác nhận giao offline đã được kiểm chứng ngày 27/11/2026 (xem bảng trên).
- Hẹn giờ tự thử lại chỉ chạy khi app đang mở; app bị đóng thì đồng bộ ở lần mở sau (không có đồng bộ nền). Chưa đo thời điểm tự thử lại trên máy thật, đã thử bằng nút "Thử lại".
- Tab "Chờ nhận hoàn" (hiện nhãn "Thất bại") hiển thị đúng đơn RETURNING của trạm gửi; phần "Lý do hoàn hàng" chỉ có khi lịch sử đơn có bước `FAILED` kèm lý do, mà dữ liệu seed không có.
