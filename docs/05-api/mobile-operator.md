# 6.4 — Mobile vận hành: nhận kiện, chụp ảnh, quét QR và xác nhận giao

Nhánh: `feature/6.4-mobile-operator`. Backend Java Spring Boot **không đổi**; mobile Flutter dùng lại API của 4.5 (nhận kiện), 4.7 (xác nhận giao), 3.4 (tệp) và 3.7 (đọc đơn). Mobile không tự quyết định nghiệp vụ: server kiểm tra phạm vi trạm, mã nhận hàng, ảnh và đổi trạng thái; app **không tự đánh dấu DELIVERED**, chỉ hiển thị kết quả server trả về.

## Phạm vi

| Có làm | Không làm |
|---|---|
| Danh sách đơn chờ nhận kiện (trạm gửi) và chờ giao khách (trạm nhận) trong phạm vi được phân công | `dispatch` và `arrive` (4.4) |
| Nhận kiện (4.5), không có ảnh vì API không có trường ảnh | Hàng đợi offline và tự gửi lại (6.5); 6.4 chỉ chuẩn hóa request để 6.5 dùng |
| Chụp hoặc chọn ảnh xác nhận, tải lên bằng luồng MinIO | Hiển thị lại ảnh bằng chứng đã lưu |
| Quét QR hoặc nhập OTP và xác nhận giao (4.7) | |

## Màn hình và route

| Route | Màn hình |
|---|---|
| `/operator` | Màn chính: trạm được phân công, hai tab "Chờ nhận kiện" và "Chờ giao khách" (có số đơn) |
| `/operator/receipts/:id` | Chi tiết và nhận kiện |
| `/operator/pickups/:id` | Xác nhận giao: mã (QR hoặc OTP), ảnh, ghi chú |
| `/operator/scan` | Quét mã QR (trả mã về màn xác nhận, không đưa mã vào URL) |

Route khách (`/customer/...`) không đổi.

## API mobile gọi (vai trò STATION_OPERATOR)

Mọi API cần `Authorization: Bearer <accessToken>`.

| Chức năng | API | Ghi chú |
|---|---|---|
| Trạm được phân công | `GET /v1/stations/monitoring` | Với operator chỉ trả trạm được phân công |
| Chờ nhận kiện | `GET /v1/station-receipts` | `SCHEDULED` ở trạm gửi được phân công; phân trang. **Không có** `packageId` và `attemptId` |
| Xem trước khi nhận | `GET /v1/orders/{id}/receipt-context` | `OrderDetail`, `senderName`, `packageId`, `attempt{id,attemptNo,slotStart,slotEnd}`; `version` làm `If-Match` |
| Nhận kiện | `POST /v1/orders/{id}/receipts` | `If-Match`; body `{attemptId, packageId, note?}`; `SCHEDULED → PREPARING` |
| Chờ giao khách | `GET /v1/orders?status=AWAITING_PICKUP` | Server trả đơn có trạm gửi **hoặc** nhận thuộc phạm vi; app lọc thêm theo trạm nhận (xem dưới) |
| Chi tiết đơn | `GET /v1/orders/{id}` | Lấy `version` cho `If-Match` |
| Upload ảnh | `POST /v1/files/upload-tickets` (`purpose: CONFIRMATION_PHOTO`) → MinIO → `POST /v1/files/{id}/complete` | Cùng luồng 3 bước với ảnh gói hàng của 6.2 |
| Xác nhận giao | `POST /v1/orders/{id}/confirmations` | `If-Match`; body có **đúng một** trong `otp` hoặc `qrToken`, cùng `photoFileId`, `note?`; chỉ operator trạm nhận; `AWAITING_PICKUP → DELIVERED` |

### Mã nhận hàng (4.7)

Khách mở mã bằng `GET /v1/orders/{id}/pickup-code`, được `otp` (6 chữ số) và `qrPayload`. Operator gửi `qrPayload` thành `qrToken`.

- `qrToken` có dạng `challengeId.chữ-ký` (UUID, dấu chấm, chữ ký base64url, tối đa 200 ký tự). App chỉ kiểm tra **hình dạng** để bỏ qua các mã QR không liên quan; chữ ký do server xác minh.
- Mã sống 30 phút kể từ lúc khách mở; khách mở lại sau khi hết hạn hoặc bị khóa thì được mã mới và mã cũ vô hiệu.
- OTP và QR **dùng chung bộ đếm sai**: sai tối đa 5 lần thì khóa.

### Giới hạn cho phạm vi dữ liệu

`GET /v1/orders` của operator có thể trả đơn mà người đó chỉ là trạm **gửi**, nhưng chỉ operator trạm **nhận** xác nhận được. Tab "Chờ giao khách" đọc hết các trang rồi chỉ giữ đơn có `dropoffStationId` nằm trong danh sách trạm do server cấp (`monitoring`). Đây là lọc hiển thị; quyền thật do server kiểm tra (đơn ngoài phạm vi trả `404`).

## Cách mobile xử lý lỗi

| Tình huống | Hành vi |
|---|---|
| Mã sai `422 PICKUP_CODE_INVALID` | Hiện thông báo của server ("Còn N lần thử"), xóa mã, giữ ảnh |
| Hết hạn `410 PICKUP_CODE_EXPIRED` | Hướng dẫn "nhờ khách mở lại mã", xóa mã |
| Bị khóa `429 PICKUP_CODE_LOCKED` | Như trên (không tự thử lại) |
| Đã dùng `409 PICKUP_CODE_USED` | Báo, xóa mã |
| `412 VERSION_CONFLICT` hoặc `409 INVALID_STATE_TRANSITION` | Tải lại đơn, báo "đơn vừa thay đổi"; giữ mã và ảnh; nhận kiện vẫn thử lại được với phiên bản mới |
| `404` | Ngoài phạm vi của operator hoặc không tồn tại (khi xác nhận: cũng có thể là ảnh không hợp lệ) |
| `428`, `400` | Thiếu `If-Match`, thiếu hoặc thừa mã, thiếu ảnh. App chặn trước nên ít khi gặp; thông báo kiểm tra dữ liệu của server là tiếng Anh nên không dùng làm chữ hiển thị |
| Mất mạng | "Đơn CHƯA được …", nút đổi thành "Gửi lại", giữ nguyên dữ liệu |
| Hết thời gian chờ | "Chưa rõ kết quả": nhận kiện thì tải lại để biết đã nhận chưa; xác nhận giao thì "Gửi lại" là an toàn |

Chỉ báo "đã nhận kiện" hoặc "đã giao" khi server trả `2xx`. Nút bị vô hiệu hóa khi đang gửi để chặn bấm đúp.

## Ảnh xác nhận

- Tối đa một ảnh cho mỗi đơn (server bắt buộc đúng một `photoFileId`); chọn bằng camera hoặc thư viện, tải lên ngay, có thử lại, chụp lại, xóa.
- `photoFileId` chỉ dùng được sau khi **tải lên và `complete` thành công**. Đang tải hoặc lỗi thì không có `fileId` và không xác nhận được.
- Chỉ JPEG, PNG, WebP; dung lượng tối đa theo `maxSizeBytes` của upload ticket (hiện 5 MB). Ảnh quá lớn chỉ có "Chụp lại"; lỗi tạm thời (mất mạng, MinIO từ chối) có "Thử lại".
- Quyền camera: `mobile_scanner` khai báo `CAMERA` trong manifest nên Android hỏi quyền lúc chạy. Bị từ chối thì `image_picker` báo `camera_access_denied` (đổi thành `PhotoPermissionDenied`) và màn quét hiển thị hướng dẫn cấp quyền kèm nút quay lại để nhập OTP.
- Ảnh đã tải mà chưa gắn vào đơn được server tự dọn sau 24 giờ.

## Hợp đồng cho 6.5 (retry và offline)

`ReceiveRequest` và `ConfirmRequest` (`features/operator/operator_models.dart`) là giá trị thuần, mỗi cái có `orderId`, `version`, `ifMatch` và `toBody()` đúng hợp đồng. `ApiFailure.isRetryable` cho biết lỗi tạm thời (mất mạng, hết thời gian chờ, 5xx, 503 kho ảnh, 429 quá nhiều yêu cầu); mã nhận hàng bị khóa **không** thuộc nhóm này.

| Thao tác | Gửi lại sau khi mất kết quả |
|---|---|
| Xác nhận giao | **An toàn** nếu giữ **nguyên** `version`, `photoFileId` và mã: server trả lại kết quả cũ kèm `Idempotent-Replayed: true` (app hiện qua `ConfirmResult.replayed`). Đổi ảnh hoặc mã khi gửi lại bị từ chối |
| Nhận kiện | **Không replay được**: gửi lại sau khi đã thành công trả `412` (phiên bản cũ) hoặc `409` (đã `PREPARING`). Phải tải lại `receipt-context` để biết trạng thái thật rồi mới quyết định |

Không được lưu OTP hoặc `qrToken` vào bộ nhớ không mã hóa; `toString` của `PickupCredential` và `ConfirmRequest` che mã để không lọt vào log.

## Kiểm thử

Chạy trong `mobile`:

```sh
flutter pub get
flutter analyze --no-pub --no-fatal-infos
flutter test --no-pub
```

Kết quả khi nộp: `flutter analyze` không có lỗi hay cảnh báo (còn 1 `info` có sẵn ở `test/session_test.dart:160`); `flutter test` **195 test đều qua**, trong đó 80 test mới:

| File | Số test | Nội dung chính |
|---|---|---|
| `operator_api_test.dart` | 21 | Parse model, `If-Match` và body, mã nhận hàng, upload `CONFIRMATION_PHOTO`, phân loại lỗi, `isRetryable` |
| `operator_receipt_test.dart` | 22 | Danh sách (phân trang, lọc theo trạm nhận), nhận kiện (412, 409, 404, offline, timeout, bấm đúp), màn hình |
| `confirmation_photo_test.dart` | 19 | Chọn và tải ảnh, từ chối quyền, ảnh quá lớn, upload dở, ánh xạ mã lỗi quyền của `image_picker` |
| `pickup_confirm_test.dart` | 18 | Xác nhận giao bằng OTP và QR, mã sai, hết hạn, khóa, replay, màn quét giả, màn hình |

Các test dùng API giả. Build: `flutter build apk --debug` thành công với `mobile_scanner`.

### Kiểm chứng với backend thật

Môi trường: Windows 11, Flutter 3.47.9, emulator Android 37, backend Spring Boot với SQL Server và MinIO bằng Docker.

Đã kiểm bằng API thật: operator gửi và operator nhận thấy đúng trạm và đúng đơn của mình; operator trạm khác nhận kiện, dispatch hoặc xác nhận đều `404`; khách gọi API nhận kiện `403`; thiếu `If-Match` `428`; nhận lại với phiên bản cũ `412`, với phiên bản mới `409`; mã sai `422` ("Còn N lần thử"), sai 5 lần `429` (nhập đúng sau đó vẫn bị khóa), mã hết hạn `410`, xác nhận đúng `200`, gửi lại y hệt `200` với `Idempotent-Replayed: true`, gửi lại đổi ảnh `409`; ảnh xác nhận ticket → MinIO → complete.

Đã thao tác trên emulator: màn chính và chi tiết nhận kiện; nhận kiện (kể cả khi mất mạng rồi gửi lại); chụp ảnh xác nhận bằng camera ảo; nhập OTP sai rồi đúng và giao hàng (có ảnh và bằng chứng trong DB).

**Chưa kiểm chứng đầy đủ:**
- Quét QR bằng camera ảo: người demo báo đã quét được mã nhưng dữ liệu demo **chưa có xác nhận nào bằng phương thức `QR`** trên server. Phần xác nhận bằng QR chỉ có test với bộ quét giả và kiểm bằng API.
- Bị khóa (5 lần sai) và mã hết hạn **trên giao diện** (đã kiểm bằng API thật và test tự động).
- Từ chối quyền camera trên thiết bị thật; thiết bị Android thật; Android hủy app khi đang mở camera (`retrieveLostData`); iOS (repo chưa có thư mục iOS).

## Hướng dẫn demo

Dữ liệu demo dùng giá trị giả; không đưa mật khẩu hay khóa thật vào repo hoặc ảnh chụp.

1. Bật Docker Desktop, `docker compose up -d`, rồi chạy backend với `MINIO_PUBLIC_ENDPOINT=http://10.0.2.2:9000` (xem `docs/05-api/mobile-customer-orders.md`).
2. Cần: một khách có địa chỉ, một điều phối viên, hai operator (một ở trạm gửi, một ở trạm nhận). Tài khoản mới đăng ký luôn là khách; nâng quyền và phân công trạm bằng `backend/scripts/5.2-assign-staff-to-stations.sql` (hoặc cập nhật `users.role_id` và bảng `staff_station_assignments`).
3. Tạo đơn bằng tài khoản khách, rồi đưa qua quy trình bằng API: điều phối viên `approve` và `schedules` (khung giờ trên lưới 30 phút UTC, sớm nhất 30 phút sau); operator gửi nhận kiện (app) và `dispatch`; operator nhận `arrive`. Khách mở mã bằng `GET /v1/orders/{id}/pickup-code`.
4. Mở emulator và chạy app (`flutter run -d emulator-5554`). Đăng nhập operator gửi: nhận kiện (tích kiểm tra, ghi chú, xác nhận). Thử mất mạng rồi gửi lại.
5. Đăng nhập operator nhận: mở đơn chờ giao, chụp ảnh xác nhận, nhập OTP sai rồi đúng; quét QR (nạp ảnh QR của `qrPayload` vào Extended controls → Camera → Virtual scene → poster, rồi dùng Alt cùng W, A, S, D và chuột để tới poster).
6. Thử từ chối quyền camera ở màn quét và quay lại nhập OTP.

### Lưu ý môi trường

- **Windows, project và pub cache khác ổ đĩa:** thêm `--android-project-arg=kotlin.incremental=false --android-project-arg=kotlin.compiler.execution.strategy=in-process` vào `flutter run` hoặc `flutter build` để né lỗi Kotlin incremental của plugin.
- **DB dev cũ tạo bằng Hibernate trước khi code dùng `@Nationalized`:** cột `users.full_name` và `order_status_history.reason` có thể còn là `varchar`, gây lỗi `The conversion from varchar to NCHAR is unsupported` (đăng nhập hoặc `dispatch` trả `409 DATA_CONFLICT`). Sửa bằng `ALTER TABLE users ALTER COLUMN full_name nvarchar(150) NOT NULL;` và `ALTER TABLE order_status_history ALTER COLUMN reason nvarchar(500) NULL;`. DB tạo bằng script SQL của dự án không gặp lỗi này.
- Nếu máy cài sẵn SQL Server trên Windows và cùng nghe cổng 1433, backend có thể nối nhầm vào đó (`Login failed for user 'sa'`).

## Phụ thuộc và thay đổi cấu hình

- Thêm `mobile_scanner ^7.5.0` (quét QR; tự khai báo quyền `CAMERA` và đặt camera là không bắt buộc; Android minSdk 23).
- `pubspec.lock`: thêm `mobile_scanner 7.5.0`.
- Sửa nhẹ code của 6.2: tách luồng upload 3 bước thành `PhotoUploader` dùng chung (hành vi giữ nguyên), thêm các loại lỗi của mã nhận hàng vào `ApiFailure`, `Station` thêm `address`, `photo_picker` thêm `PhotoPermissionDenied`.

## Kiểm chứng bổ sung (27/11/2026)

Emulator Android 37 và backend thật (`sqlserver,demo`), tài khoản `operator@demo.local`:

| Ca | Kết quả |
|---|---|
| Trang vận hành sau khi nhận kiện ở nơi khác | Danh sách tự cập nhật: đơn đã nhận rời tab "Chờ nhận", đơn `AWAITING_PICKUP` hiện ở "Chờ giao" |
| Mở đơn chờ giao, chụp ảnh bằng camera ảo | Ảnh tải lên MinIO, hiện "Đã tải lên", nút xác nhận chỉ sáng khi có ảnh và mã |
| Nhập OTP đúng, xác nhận | Hộp thoại xác nhận; server `200`, đơn `DELIVERED`, phương thức `OTP` |
| Nhập OTP sai | Server `422`; app báo "Còn 4 lần thử" và xoá ô nhập |

**Vẫn chưa kiểm chứng:** quét **QR** bằng camera ảo và việc server ghi phương thức `QR`. App khách hiện chưa vẽ được mã QR (chưa có thư viện tạo QR) nên không có ảnh QR để đưa vào camera ảo. Chưa test từ chối quyền camera trên thiết bị thật.
