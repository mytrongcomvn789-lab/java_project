# Báo cáo kiểm thử APDDMP-17 — Xác thực (task 2.5)

Phạm vi: đăng ký, đăng nhập, JWT, refresh token, đăng xuất. Tài liệu thiết kế: [`../03-architecture/auth-design.md`](../03-architecture/auth-design.md). Hợp đồng API: [`../05-api/auth-api.md`](../05-api/auth-api.md).

## Kết quả

- **Maven verify: BUILD SUCCESS** trên máy phát triển, gồm các bài kiểm thử tự động của 2.5.
- **Kiểm thử tay trên API thật: 9 bước đều cho kết quả đúng như mong đợi**.
- **Kiểm thử tích hợp với web: đạt**. Trang đăng nhập gọi `POST /api/v1/auth/login` trả `200`, CORS cho phép nguồn `http://localhost:5173`.

| Bộ kiểm thử                |           Số test | Lỗi |
| -------------------------- | ----------------: | --: |
| `AuthServiceTest`          |                13 |   0 |
| `JwtAccessTokenIssuerTest` |                 6 |   0 |
| Kiểm thử tay API           |            9 bước |   0 |
| Kiểm thử tích hợp web      | 1 luồng đăng nhập |   0 |

## Môi trường

| Hạng mục          | Giá trị                                                          |
| ----------------- | ---------------------------------------------------------------- |
| Hệ điều hành      | Windows (PowerShell)                                             |
| Java              | Temurin 21.0.13                                                  |
| Maven             | 3.9.9                                                            |
| Spring Boot       | 4.0.5                                                            |
| Cơ sở dữ liệu     | H2 trong bộ nhớ (profile `h2`), dữ liệu mất khi tắt ứng dụng     |
| Cổng và đường dẫn | backend `http://localhost:5000/api`, web `http://localhost:5173` |
| Khóa ký JWT       | mặc định để chạy thử (`APP_JWT_KEY` chưa đặt)                    |

## 1. Kiểm thử tự động

Chạy bằng `mvn -f backend/pom.xml verify`. Mục tiêu là kiểm tra luật nghiệp vụ mà không cần máy chủ đang chạy.

### 1.1 `AuthServiceTest` (13 test)

| #   | Hành vi được kiểm tra                                                                                      |
| --- | ---------------------------------------------------------------------------------------------------------- |
| 1   | Đăng ký luôn gán `CUSTOMER` và chuẩn hóa email (cắt khoảng trắng, chữ thường)                              |
| 2   | Đăng ký email đã tồn tại bị từ chối (`EMAIL_ALREADY_EXISTS`)                                               |
| 3   | Hai yêu cầu đăng ký cùng email đồng thời: vi phạm ràng buộc duy nhất được đổi thành `EMAIL_ALREADY_EXISTS` |
| 4   | Mật khẩu quá 72 byte bị từ chối                                                                            |
| 5   | Đăng nhập đúng, chỉ lưu **bản băm** của refresh token                                                      |
| 6   | Sai mật khẩu và sai email cho cùng một lỗi `INVALID_CREDENTIALS`                                           |
| 7   | Tài khoản bị khóa chỉ báo `ACCOUNT_LOCKED` sau khi mật khẩu đúng                                           |
| 8   | Refresh xoay vòng: token cũ bị thu hồi, cấp token mới                                                      |
| 9   | Refresh với token không tồn tại bị từ chối                                                                 |
| 10  | Refresh với token hết hạn bị từ chối                                                                       |
| 11  | Dùng lại refresh token đã thu hồi: thu hồi mọi phiên của người dùng                                        |
| 12  | Refresh khi tài khoản bị khóa: thu hồi mọi phiên và báo `ACCOUNT_LOCKED`                                   |
| 13  | Đăng xuất thu hồi token và idempotent (token lạ vẫn thành công)                                            |

### 1.2 `JwtAccessTokenIssuerTest` (6 test)

| #   | Hành vi được kiểm tra                                  |
| --- | ------------------------------------------------------ |
| 1   | Token cấp ra giải mã được và có đủ claim cần thiết     |
| 2   | `expiresIn` (giây) khớp thời hạn cấu hình              |
| 3   | Token bị sửa nội dung bị từ chối                       |
| 4   | Token hết hạn bị từ chối                               |
| 5   | Token sai audience hoặc ký bằng khóa khác bị từ chối   |
| 6   | Khóa ký ngắn hơn 32 ký tự làm ứng dụng không khởi động |

## 2. Kiểm thử tay API

Cách làm: chạy backend với profile `h2`, gọi API bằng PowerShell (hàm `Call` ở mục 5).

| #   | Tình huống                             | Dữ liệu gửi                                                                                           | Kết quả mong đợi                 | Kết quả thực tế                                                                                        |
| --- | -------------------------------------- | ----------------------------------------------------------------------------------------------------- | -------------------------------- | ------------------------------------------------------------------------------------------------------ |
| 1   | Đăng ký, kèm trường `role`             | `{"email":"an@example.com","password":"Passw0rdOK","fullName":"Nguyen Van An","role":"SYSTEM_ADMIN"}` | `201`, vai trò vẫn là `CUSTOMER` | Đạt: `201`, `"role":"CUSTOMER"`                                                                        |
| 2   | Đăng ký trùng email                    | cùng email như bước 1                                                                                 | `409`                            | Đạt: `409`                                                                                             |
| 3   | Dữ liệu sai                            | email `x`, mật khẩu `123`, họ tên rỗng                                                                | `400`                            | Đạt: `400`                                                                                             |
| 4   | Đăng nhập sai mật khẩu                 | mật khẩu `SaiMatKhau1`                                                                                | `401`                            | Đạt: `401`                                                                                             |
| 5   | Gọi API khác khi chưa đăng nhập        | không có token                                                                                        | `401` `AUTH_REQUIRED`            | Đạt: `401`, `code: "AUTH_REQUIRED"`                                                                    |
| 6   | Đăng nhập đúng                         | email và mật khẩu đúng                                                                                | `200`, có cặp token và `user`    | Đạt: có `accessToken`, `refreshToken`, `tokenType: "Bearer"`, `expiresIn: 900`, `user.role = CUSTOMER` |
| 7   | Refresh với refresh token vừa nhận     | `refreshToken` của bước 6                                                                             | `200`, cặp token mới             | Đạt: nhận được cặp token mới                                                                           |
| 8   | Refresh lại với **token cũ** (đã dùng) | cùng `refreshToken` ở bước 6                                                                          | `401` (phát hiện dùng lại)       | Đạt: `401`                                                                                             |
| 9   | Đăng nhập lại rồi đăng xuất            | `refreshToken` mới                                                                                    | `204`                            | Đạt: `204`                                                                                             |

Ghi chú: lượt kiểm thử tay này làm trước khi gộp task 2.6. Từ khi có 2.6, đường dẫn chưa khai báo quyền bị từ chối theo mặc định, nên với bước 5 và các API nghiệp vụ chưa tồn tại, kết quả có thể là `401` hoặc `403` tùy trường hợp. Điều này không ảnh hưởng đến 4 API xác thực.

## 3. Kiểm thử tích hợp với web

Chạy cả backend và web (`npm run dev`), đăng nhập trên giao diện thật rồi xem tab Network của trình duyệt.

| #   | Quan sát                                       | Kết quả                                                                           |
| --- | ---------------------------------------------- | --------------------------------------------------------------------------------- |
| 1   | `POST http://localhost:5000/api/v1/auth/login` | Trạng thái `200 OK`                                                               |
| 2   | Header phản hồi `Access-Control-Allow-Origin`  | `http://localhost:5173` (CORS đúng)                                               |
| 3   | Payload gửi đi                                 | `email` và `password` đã nhập                                                     |
| 4   | Nội dung phản hồi                              | Có `accessToken`, `refreshToken`, `tokenType: "Bearer"`, `expiresIn: 900`, `user` |
| 5   | Ô mật khẩu để trống rồi bấm Đăng nhập          | Web báo "Vui lòng nhập mật khẩu" ngay, không gọi API                              |

## 4. Cách chạy lại

**Tự động:**

```powershell
mvn -B -f backend/pom.xml verify
```

**Kiểm thử tay.** Chạy backend không cần PostgreSQL:

```powershell
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=h2"
```

Rồi ở cửa sổ khác:

```powershell
$base = "http://localhost:5000/api/v1/auth"
function Call($m,$u,$b,$t){
  $h=@{}; if($t){$h["Authorization"]="Bearer $t"}
  try { $r = Invoke-WebRequest -Method $m -Uri $u -ContentType "application/json" -Body $b -Headers $h -UseBasicParsing; "$($r.StatusCode) $($r.Content)" }
  catch { "$([int]$_.Exception.Response.StatusCode) $($_.ErrorDetails.Message)" }
}

# 1. Đăng ký (role gửi thêm sẽ bị bỏ qua)
Call Post "$base/register" '{"email":"an@example.com","password":"Passw0rdOK","fullName":"Nguyen Van An","role":"SYSTEM_ADMIN"}'
# 2. Trùng email
Call Post "$base/register" '{"email":"an@example.com","password":"Passw0rdOK","fullName":"Nguyen Van An"}'
# 3. Dữ liệu sai
Call Post "$base/register" '{"email":"x","password":"123","fullName":""}'
# 4. Sai mật khẩu
Call Post "$base/login" '{"email":"an@example.com","password":"SaiMatKhau1"}'
# 5. Chưa đăng nhập
Call Get "http://localhost:5000/api/v1/orders"
# 6. Đăng nhập đúng
$t = Invoke-RestMethod -Method Post -Uri "$base/login" -ContentType "application/json" -Body '{"email":"an@example.com","password":"Passw0rdOK"}'
# 7. Refresh
$t2 = Invoke-RestMethod -Method Post -Uri "$base/refresh" -ContentType "application/json" -Body (@{refreshToken=$t.refreshToken} | ConvertTo-Json)
# 8. Dùng lại token cũ: phải 401
Call Post "$base/refresh" (@{refreshToken=$t.refreshToken} | ConvertTo-Json)
# 9. Đăng xuất bằng token mới: phải 204
Call Post "$base/logout" (@{refreshToken=$t2.refreshToken} | ConvertTo-Json)
```

Bước 8 và 9 cho kết quả đúng chỉ khi chạy theo thứ tự trên.

## 5. Giới hạn của lượt kiểm thử

- **Chưa có kiểm thử HTTP tự động riêng cho 4 API xác thực** trong thư mục test của 2.5. Việc kiểm tra đầu cuối ở mục 2 và 3 làm bằng tay. Bộ `RbacIntegrationTest` của 2.6 có kiểm tra thêm một số hành vi liên quan (ví dụ đăng ký gửi thêm `role=SYSTEM_ADMIN` vẫn tạo `CUSTOMER`).
- **Chưa kiểm thử hai request refresh song song** với cùng một token để xác nhận khóa dòng (`PESSIMISTIC_WRITE`) hoạt động trên cơ sở dữ liệu thật; unit test chỉ kiểm tra logic.
- **Chưa kiểm thử giới hạn số lần đăng nhập sai và khóa tạm** vì tính năng chưa có.
- **Chưa kiểm thử tải** và chưa đo thời gian phản hồi.
- **Chưa kiểm thử trên PostgreSQL** cho phần xác thực của 2.5; các lượt trên đều dùng H2 trong bộ nhớ.
- Phần giao diện đăng ký chưa có trên web, nên đăng ký chỉ được kiểm thử qua API.
