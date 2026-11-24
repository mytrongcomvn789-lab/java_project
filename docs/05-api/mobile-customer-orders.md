# 6.2 — Mobile khách hàng: tạo đơn, chọn địa chỉ, xem và hủy đơn

Nhánh: `feature/6.2-mobile-orders`. Backend Java Spring Boot **không đổi** trong thay đổi này; mobile Flutter dùng lại các API có sẵn của giai đoạn 3 (3.1 địa chỉ, 3.2 trạm, 3.3 gói hàng, 3.4 tệp, 3.6 tạo/hủy đơn, 3.7 danh sách/chi tiết). Mobile **không** tự quyết định nghiệp vụ (hợp lệ dữ liệu, chuyển trạng thái, phạm vi dữ liệu): server là nguồn quyết định, mobile chỉ hiển thị lỗi của server.

## Màn hình và route

| Route | Màn hình | Ghi chú |
|---|---|---|
| `/customer` | Danh sách đơn của tôi | Lọc theo trạng thái, phân trang 20 đơn, kéo để làm mới |
| `/customer/orders/:id/detail` | Chi tiết đơn | Hủy đơn có xác nhận; nút "Theo dõi đơn" |
| `/customer/orders/:id` | Theo dõi realtime (6.3) | **Không đổi**, đây là route để tích hợp theo dõi |
| `/customer/new-order` | Tạo đơn | Trạm, địa chỉ, gói hàng, ảnh |
| `/customer/addresses` | Sổ địa chỉ | Thêm, sửa, xóa. `?pick=1&selected=<id>` là chế độ chọn, trả địa chỉ về màn gọi |

Chỉ vai trò `CUSTOMER` vào được các route trên (điều hướng theo vai trò của 6.1).

## API mobile gọi

Base URL mặc định `http://10.0.2.2:5000/api` (địa chỉ máy chủ nhìn từ emulator Android), đổi bằng `--dart-define=API_BASE_URL=...`. Mọi API cần `Authorization: Bearer <accessToken>`.

| Chức năng | API | Ghi chú |
|---|---|---|
| Danh sách trạm | `GET /v1/stations` | Chỉ trạm `ACTIVE` được chọn làm trạm gửi/nhận |
| Danh sách địa chỉ | `GET /v1/addresses?page&pageSize` | Mobile đọc lần lượt các trang (50/trang) cho tới hết |
| Thêm/sửa/xóa địa chỉ | `POST`, `PUT /{id}`, `DELETE /{id}` | Body: `label`, `recipientName`, `phone`, `addressLine`, `latitude`, `longitude` |
| Tạo đơn | `POST /v1/orders` | Bắt buộc header `Idempotency-Key`. `201` mới; `200` + `Idempotent-Replayed: true` khi gửi lại cùng khóa |
| Danh sách đơn | `GET /v1/orders` | `status`, `from`, `to` (ISO-8601 UTC), `sort=createdAt,desc`, `page` từ 1, `pageSize` tối đa 100 |
| Chi tiết đơn | `GET /v1/orders/{id}` | Có `history`; `version` dùng làm `If-Match` |
| Hủy đơn | `POST /v1/orders/{id}/cancel` | Bắt buộc `If-Match: "<version>"`; chỉ đơn `PENDING` |
| Xin quyền tải ảnh | `POST /v1/files/upload-tickets` | `{purpose:"PACKAGE_PHOTO", contentType, sizeBytes}` |
| Tải ảnh | `POST <upload.url>` (MinIO) | multipart, các `fields` theo thứ tự, field `file` **cuối cùng**; không gửi Bearer |
| Xác nhận ảnh | `POST /v1/files/{id}/complete` | Server kiểm tra dung lượng và loại thật |

Body tạo đơn:

```json
{
  "pickupStationId": "<uuid trạm ACTIVE>",
  "dropoffStationId": "<uuid trạm ACTIVE khác trạm gửi>",
  "recipientAddressId": "<uuid địa chỉ của khách>",
  "package": {
    "description": "Hộp quà",
    "weightGrams": 1200,
    "lengthCm": 30, "widthCm": 20, "heightCm": 10,
    "photoFileIds": []
  }
}
```

Giới hạn do server kiểm tra (cấu hình `app.order-package`): trọng lượng tối đa 5000 g, mỗi chiều tối đa 50 cm, tối đa 3 ảnh, ảnh `image/jpeg`, `image/png`, `image/webp`. Mobile chỉ dùng các số này để gợi ý giao diện; khi sai, mobile hiển thị thông báo của server.

## Cách mobile xử lý lỗi

Mọi lỗi được phân loại ở `core/network/api_failure.dart`.

| Tình huống | Cách xử lý |
|---|---|
| Không tới được máy chủ | "Đơn CHƯA được tạo", nút đổi thành "Gửi lại", giữ nguyên dữ liệu đã nhập |
| Hết thời gian chờ sau khi đã gửi | Chưa rõ kết quả: báo "có thể đơn đã được tạo"; "Gửi lại" an toàn vì dùng cùng `Idempotency-Key` |
| `400` | Hiện thông báo của server trên form, giữ dữ liệu để sửa (trạm trùng, trạm không hoạt động, trọng lượng quá giới hạn…) |
| `404` | Địa chỉ/đơn không tồn tại hoặc không thuộc khách |
| `409 INVALID_STATE_TRANSITION` | Đơn không còn chờ duyệt: tải lại chi tiết, báo "đơn vừa thay đổi", ẩn nút hủy |
| `412 VERSION_CONFLICT` | Dữ liệu đã cũ: tải lại bản mới nhất và báo người dùng |
| `409 IDEMPOTENCY_KEY_CONFLICT` | Lần gửi sau dùng khóa mới |
| `413`, `415` (ảnh) | Báo ảnh quá lớn / sai loại; ảnh có nút thử lại hoặc xóa |
| `5xx` | Không hiển thị `message` của server (tránh lộ chi tiết nội bộ) |

Chỉ báo "tạo thành công" khi server trả `2xx`.

### Chống tạo đơn trùng

- Nút "Tạo đơn" bị vô hiệu hóa khi đang gửi, và controller bỏ qua lần gọi thứ hai.
- `Idempotency-Key` (UUID v4) gắn với nội dung đơn: gửi lại **cùng nội dung** dùng **cùng khóa**; nội dung đổi thì sinh khóa mới.
- Ảnh đang tải hoặc tải lỗi thì chặn gửi đơn.

### Dữ liệu cũ khi hủy đơn

Hủy dùng `version` đang xem làm `If-Match`. Màn chi tiết tự tải lại khi có thông báo realtime (6.3), nên đơn vừa được duyệt thường tự cập nhật và ẩn nút hủy trước khi người dùng bấm.

### Đổi tài khoản

Danh sách, chi tiết, địa chỉ và form tạo đơn đều gắn với user đang đăng nhập: đăng xuất rồi đăng nhập tài khoản khác thì dữ liệu cũ bị bỏ ngay, phản hồi đến muộn của phiên cũ bị bỏ qua.

## Kiểm thử

Chạy trong `mobile`:

```sh
flutter pub get
flutter analyze --no-pub --no-fatal-infos
flutter test --no-pub
```

Kết quả khi nộp: `flutter analyze` không có lỗi hay cảnh báo (còn 1 `info` có sẵn ở `test/session_test.dart:160`); `flutter test` **115 test đều qua**, trong đó 64 test mới:

| File | Số test | Nội dung chính |
|---|---|---|
| `customer_orders_api_test.dart` | 17 | Parse model, header `Idempotency-Key`/`If-Match`, upload 3 bước, phân loại lỗi |
| `order_list_test.dart` | 8 | Phân trang, đổi bộ lọc, bỏ phản hồi muộn, lỗi và thử lại, đổi tài khoản, màn hình |
| `order_detail_test.dart` | 11 | Hủy thành công, bấm đúp, 409, 412, offline, timeout, xác nhận trước khi hủy |
| `address_test.dart` | 11 | Kiểm tra nhập, thêm/sửa/xóa, chế độ chọn, bấm đúp lưu |
| `order_form_test.dart` | 17 | Gửi một lần, offline và timeout dùng lại khóa, ảnh, giới hạn, đổi tài khoản |

Các test dùng API giả (không cần backend). Build: `flutter build apk --debug` thành công trên Windows (xem lưu ý Kotlin bên dưới).

### Kiểm chứng với backend thật

Môi trường: Windows 11, Flutter 3.47.9, emulator Android 37 (`Medium_Phone_API_37.0`), backend Spring Boot trên SQL Server và MinIO bằng Docker.

Đã thao tác trên emulator: thêm địa chỉ (có kiểm tra nhập sai), tạo đơn (có bấm đúp), thêm ảnh bằng camera ảo của emulator, xem chi tiết, hủy có xác nhận, lọc theo trạng thái, cuộn danh sách trên 20 đơn, đơn được điều phối viên duyệt từ bên ngoài (màn hình tự cập nhật), mất mạng khi tạo đơn rồi gửi lại (chỉ có một đơn trong DB), đăng xuất sang tài khoản khác (không thấy đơn).

Đã kiểm bằng API thật: hủy đơn đã duyệt `409`, hủy bằng phiên bản cũ `412`, hủy thiếu `If-Match` `428`, khách khác dùng địa chỉ/đơn của người khác `404`.

**Chưa kiểm chứng:** nhánh xử lý `409`/`412` trên giao diện app (realtime đã cập nhật màn hình trước khi bấm hủy nên chỉ có test tự động với API giả); thiết bị Android thật, camera thật và thư viện ảnh có ảnh; iOS (dự án chưa có thư mục iOS).

## Hướng dẫn demo

Dữ liệu demo dùng giá trị giả; không đưa mật khẩu hay khóa thật vào repo hay ảnh chụp.

1. Bật Docker Desktop, rồi tại thư mục gốc: `docker compose up -d`.
2. Chạy backend. Emulator không truy cập được `localhost`, nên phải đặt địa chỉ MinIO công khai là `10.0.2.2`:
   ```powershell
   cd backend
   $env:DB_PASSWORD = '<mật khẩu sa trong .env>'
   $env:MINIO_ACCESS_KEY = '<MINIO_ROOT_USER>'
   $env:MINIO_SECRET_KEY = '<MINIO_ROOT_PASSWORD>'
   $env:MINIO_PUBLIC_ENDPOINT = 'http://10.0.2.2:9000'
   mvn spring-boot:run
   ```
3. Cần ít nhất 2 trạm `ACTIVE` (`GET /v1/stations`; dữ liệu mẫu ở `backend/scripts/3.2-station-sample-data.sql`).
4. Đăng ký hai tài khoản khách bằng `POST /v1/auth/register` (nhóm `business-v1` trên Swagger).
5. Mở emulator và chạy app:
   ```powershell
   flutter emulators --launch Medium_Phone_API_37.0
   cd mobile
   flutter run -d emulator-5554
   ```
6. Đăng nhập tài khoản A, rồi đi qua:
   - Thêm địa chỉ (thử lưu khi để trống; số điện thoại `0901234567`, vĩ độ `10.77`, kinh độ `106.7`).
   - Tạo đơn: chọn hai trạm khác nhau, địa chỉ, nhập gói hàng, thêm ảnh; bấm "Tạo đơn" hai lần thật nhanh, chỉ có một đơn.
   - Chi tiết đơn: chọn "Giữ đơn" (không đổi), rồi hủy và xác nhận (thành "Đã hủy").
   - Mất mạng: điền đủ form khi còn mạng, bật chế độ máy bay, bấm "Tạo đơn" (hiện "Đơn CHƯA được tạo"); tắt chế độ máy bay, bấm "Gửi lại" (đúng một đơn).
   - Đăng xuất, đăng nhập tài khoản B: không thấy đơn và địa chỉ của A.
7. Thử đơn được duyệt từ bên ngoài: cần tài khoản `DISPATCHER` được phân công trạm (`POST /v1/orders/{id}/approve`).

### Lưu ý môi trường (Windows)

- Nếu project và pub cache ở khác ổ đĩa (ví dụ project ở `D:`, pub cache ở `C:`), `image_picker_android` có thể lỗi biên dịch Kotlin kiểu "Could not close incremental caches". Cách né không sửa repo: thêm `--android-project-arg=kotlin.incremental=false --android-project-arg=kotlin.compiler.execution.strategy=in-process` vào `flutter run` hoặc `flutter build`.
- Nếu máy cài sẵn SQL Server trên Windows và cũng nghe cổng 1433, backend có thể nối nhầm vào bản đó và báo `Login failed for user 'sa'`. Tạm dừng dịch vụ đó hoặc đổi cổng của Docker.

## Phụ thuộc và thay đổi cấu hình

- Thêm `image_picker ^1.2.4` (chọn ảnh từ thư viện hoặc camera, thu nhỏ tối đa 1600 px, chất lượng 85). Android không cần khai báo quyền thêm.
- `pubspec.lock`: thêm các gói của `image_picker`; cận dưới SDK Flutter trong lock đổi thành `>=3.47.0`.
