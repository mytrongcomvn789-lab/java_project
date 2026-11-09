# Lưu trữ ảnh với MinIO — khởi chạy, cấu hình, dọn dẹp (Task 3.3)

## 1. Khởi chạy môi trường dev

1. Sao chép mẫu cấu hình và điền giá trị thật (file `.env` đã bị `.gitignore`, **không commit**):
   ```bash
   cp .env.example .env
   ```
   Khóa app (`MINIO_ACCESS_KEY` ≥ 3 ký tự, `MINIO_SECRET_KEY` ≥ 8 ký tự) khác với tài khoản root của MinIO.
2. Chạy hạ tầng: `docker compose up -d`.
   - `minio`: API cổng 9000, Console cổng 9001 (http://localhost:9001, đăng nhập bằng tài khoản root).
   - `minio-init`: chạy một lần, tạo bucket **private** (`mc anonymous set none`) và tài khoản app.
3. Đặt biến môi trường cho backend (Spring không tự đọc `.env`):
   - PowerShell: `$env:MINIO_ACCESS_KEY="..."; $env:MINIO_SECRET_KEY="..."`
   - bash: `export MINIO_ACCESS_KEY=... MINIO_SECRET_KEY=...`
4. Chạy backend: `mvn -f backend/pom.xml spring-boot:run`. Thiếu khóa MinIO thì app vẫn chạy nhưng API tệp trả `503 STORAGE_UNAVAILABLE`.

## 2. Biến cấu hình

| Biến / thuộc tính                      | Mặc định                | Ý nghĩa                                                                           |
| -------------------------------------- | ----------------------- | --------------------------------------------------------------------------------- |
| `MINIO_ENDPOINT`                       | `http://localhost:9000` | địa chỉ backend gọi MinIO                                                         |
| `MINIO_PUBLIC_ENDPOINT`                | = `MINIO_ENDPOINT`      | địa chỉ **trình duyệt** truy cập MinIO (đặt khác khi backend chạy trong Docker)   |
| `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY` | không có                | khóa của app, không có giá trị mặc định                                           |
| `MINIO_BUCKET`                         | `smartdrone-files`      | tên bucket                                                                        |
| `app.storage.max-file-size-bytes`      | `5242880` (5 MiB)       | đề xuất, chờ nhóm chốt                                                            |
| `app.storage.upload-ticket-ttl`        | `PT5M`                  | đề xuất, chờ nhóm chốt                                                            |
| `app.storage.download-link-ttl`        | `PT5M`                  | đề xuất, chờ nhóm chốt                                                            |
| `app.storage.allowed-content-types`    | jpeg, png, webp         | chỉ 3 loại này được hỗ trợ kiểm tra nội dung; thêm loại phải sửa `ImageSignature` |
| `app.storage.max-pending-per-user`     | `20`                    | số ticket đang chờ tối đa mỗi người                                               |
| `app.storage.pending-grace`            | `PT1H`                  | chờ thêm sau khi ticket hết hạn rồi mới dọn                                       |
| `app.storage.unattached-retention`     | `PT24H`                 | giữ tệp READY chưa gắn đơn bao lâu                                                |
| `app.storage.cleanup-interval`         | `PT10M`                 | chu kỳ chạy job dọn                                                               |

**CORS:** trình duyệt upload thẳng lên MinIO nên MinIO phải cho phép origin của web (`MINIO_API_CORS_ALLOW_ORIGIN` trong `docker-compose.yml`, mặc định `http://localhost:5173`). Với môi trường khác, đổi giá trị này.

## 3. Bảo mật

- Bucket private; không có policy ẩn danh. Truy cập chỉ qua link ký có hạn do backend cấp sau khi kiểm tra quyền.
- Secret chỉ nằm trong biến môi trường/`.env` (bị `.gitignore`); repo chỉ chứa `.env.example`. Backend không log secret, URL đã ký hay object key.
- App dùng tài khoản riêng, không dùng tài khoản root. Môi trường thật nên giới hạn policy của tài khoản này chỉ trên bucket của app (dev đang dùng policy `readwrite` có sẵn cho tiện).
- Mọi kiểm tra quyền dựa trên `ownerId` lấy từ JWT, không từ request.

## 4. Dọn file tạm và chính sách giữ ảnh

Job `FileCleanupService` chạy theo `cleanup-interval`:

| Đối tượng                | Điều kiện                                        | Hành động                               |
| ------------------------ | ------------------------------------------------ | --------------------------------------- |
| Ticket `PENDING_UPLOAD`  | quá hạn ticket + `pending-grace`                 | xóa object (nếu có), đánh dấu `EXPIRED` |
| Tệp `READY` chưa gắn đơn | quá `unattached-retention` kể từ lúc upload xong | xóa object, đánh dấu `DELETED`          |
| Tệp `REJECTED`           | object bị xóa ngay khi `complete` từ chối        | không cần dọn thêm                      |
| Tệp `ATTACHED`           | **không bao giờ bị job này xóa**                 | giữ cùng vòng đời đơn hàng              |

**Chính sách giữ ảnh đã gắn với đơn (đề xuất, cần nhóm chốt):** giữ trong thời gian đơn còn hiệu lực xử lý và một khoảng sau khi đơn đóng (ví dụ 12 tháng, phục vụ khiếu nại). Việc xóa theo thời hạn này cần trạng thái đơn hàng nên **chưa nằm trong task 3.3**; sẽ làm khi có module đơn hàng.

Bản ghi metadata (`stored_files`) được giữ để truy vết, kể cả khi object đã bị dọn.

## 5. Kiểm thử

- Không cần MinIO: `mvn -f backend/pom.xml test` (gồm `FileServiceTest`, `ImageSignatureTest`, ma trận RBAC).
- Với MinIO thật: đặt `MINIO_TEST_ENDPOINT`, `MINIO_TEST_ACCESS_KEY`, `MINIO_TEST_SECRET_KEY` rồi chạy `mvn -f backend/pom.xml test -Dtest=MinioFileFlowTest`. Bộ test tự tạo bucket `smartdrone-it-files`, kiểm tra upload/xem ảnh, link hết hạn, bucket không đọc được ẩn danh, quá cỡ, ảnh giả, object không tồn tại, và người khác không lấy được link bằng cách đổi `fileId`.
- CI (`backend-ci.yml`) tự chạy MinIO với khóa ngẫu nhiên cho mỗi lần chạy.

## 6. Demo nhanh

1. Đăng nhập tài khoản CUSTOMER, lấy access token.
2. `POST /api/v1/files/upload-tickets` với `{"purpose":"PACKAGE_PHOTO","contentType":"image/png","sizeBytes":12345}`.
3. Gửi multipart tới `upload.url` với toàn bộ `upload.fields`, field `file` ở cuối.
4. `POST /api/v1/files/{fileId}/complete` → `status: READY`.
5. `GET /api/v1/files/{fileId}/download-link` → mở `url` trong trình duyệt xem ảnh; sau 5 phút link không còn dùng được.
6. Đăng nhập tài khoản khác, gọi lại bước 5 với cùng `fileId` → `404`.

## 7. Xử lý sự cố

| Triệu chứng                                            | Nguyên nhân thường gặp                                                        |
| ------------------------------------------------------ | ----------------------------------------------------------------------------- |
| `503 STORAGE_UNAVAILABLE`                              | chưa đặt `MINIO_ACCESS_KEY/SECRET_KEY`, hoặc MinIO chưa chạy                  |
| Upload từ web báo lỗi CORS                             | origin web chưa nằm trong `MINIO_API_CORS_ALLOW_ORIGIN`                       |
| MinIO trả `AccessDenied`/`Invalid according to Policy` | sai thứ tự field, thiếu field, `Content-Type` khác khai báo, hoặc file quá cỡ |
| `complete` trả `409 FILE_NOT_UPLOADED`                 | chưa upload xong hoặc ticket đã hết hạn; xin ticket mới                       |
| Link xem không mở được từ trình duyệt                  | `MINIO_PUBLIC_ENDPOINT` chưa trỏ tới địa chỉ trình duyệt truy cập được        |
