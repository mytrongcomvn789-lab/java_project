# API xác thực — Task 2.5 (APDDMP-17)

Phạm vi: đăng ký, đăng nhập, JWT, refresh token, đăng xuất. Hợp đồng máy đọc: `auth.openapi.yaml` (cùng thư mục).
Căn cứ: FR-01, NFR-02, đặc tả API APDDMP-9 (base `/api/v1`, ID dạng UUID, JSON camelCase, thời gian ISO-8601 UTC).
Không thuộc 2.5: phân quyền theo vai trò (2.6), `GET/PATCH /me` và đổi mật khẩu (2.7), form đăng nhập web (2.8).

## 1. Endpoint

Đường dẫn đầy đủ = `http://localhost:5000/api` + đường dẫn bên dưới.

| Method | Đường dẫn | Mục đích | Cần access token | Request | Response thành công |
|---|---|---|---|---|---|
| POST | `/v1/auth/register` | Đăng ký tài khoản khách hàng | Không | `RegisterRequest` | `201` + `UserProfile` |
| POST | `/v1/auth/login` | Đăng nhập | Không | `LoginRequest` | `200` + `TokenResponse` |
| POST | `/v1/auth/refresh` | Đổi refresh token lấy cặp token mới | Không | `RefreshRequest` | `200` + `TokenResponse` |
| POST | `/v1/auth/logout` | Đăng xuất thiết bị hiện tại | Không | `RefreshRequest` | `204` |

Mọi endpoint khác yêu cầu header `Authorization: Bearer <accessToken>`.
Response của login và refresh có `Cache-Control: no-store`.

## 2. Quy tắc nghiệp vụ

- **Register luôn gán role `CUSTOMER`.** Request không có trường `role`; nếu client gửi thêm `role` thì bị bỏ qua. Muốn có DISPATCHER, STATION_OPERATOR, LOGISTICS_MANAGER, SYSTEM_ADMIN thì quản trị viên đổi role (task 2.6/2.7).
- Register không tự đăng nhập; client gọi tiếp `/login`.
- Email được cắt khoảng trắng và đổi chữ thường trước khi kiểm tra trùng hoặc lưu.
- Mật khẩu: 8–72 ký tự, tối đa 72 byte UTF-8 (giới hạn của BCrypt), có ít nhất 1 chữ cái và 1 chữ số. Lưu dạng BCrypt.
- Đăng nhập sai email hoặc sai mật khẩu đều trả cùng một lỗi `INVALID_CREDENTIALS`. Tài khoản bị khóa (`enabled = false`) chỉ báo `ACCOUNT_LOCKED` sau khi mật khẩu đúng.
- Khi đăng nhập thành công, mỗi thiết bị có một refresh token riêng.

## 3. DTO

### RegisterRequest
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| email | string | có | định dạng email, tối đa 150 ký tự, duy nhất (không phân biệt hoa thường) |
| password | string | có | 8–72 ký tự, ≤ 72 byte UTF-8, có chữ cái và chữ số |
| fullName | string | có | không rỗng, tối đa 150 ký tự |
| phone | string | không | `^\+?[0-9]{9,15}$` |

### LoginRequest
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| email | string | có | không rỗng, tối đa 150 ký tự |
| password | string | có | không rỗng, tối đa 200 ký tự |

### RefreshRequest (dùng cho `/refresh` và `/logout`)
| Trường | Kiểu | Bắt buộc | Ràng buộc |
|---|---|---|---|
| refreshToken | string | có | không rỗng, tối đa 200 ký tự |

### UserProfile
| Trường | Kiểu | Ghi chú |
|---|---|---|
| id | string (UUID) | |
| email | string | chữ thường |
| fullName | string | |
| phone | string hoặc null | |
| role | string | `CUSTOMER`, `DISPATCHER`, `STATION_OPERATOR`, `LOGISTICS_MANAGER`, `SYSTEM_ADMIN` |
| createdAt | string (ISO-8601 UTC) | |

Không bao giờ trả `passwordHash`.

### TokenResponse
| Trường | Kiểu | Ghi chú |
|---|---|---|
| accessToken | string | JWT, mặc định sống 15 phút |
| refreshToken | string | chuỗi ngẫu nhiên 43 ký tự, mặc định sống 7 ngày, dùng một lần |
| tokenType | string | luôn là `Bearer` |
| expiresIn | integer | số giây hiệu lực của accessToken (mặc định 900) |
| user | UserProfile | thông tin người dùng, để web biết role ngay |

## 4. Token

**Access token** là JWT ký HS256.

| Claim | Ý nghĩa |
|---|---|
| sub | id người dùng (UUID) |
| email | email |
| role | một trong 5 vai trò; backend đổi thành quyền `ROLE_<role>` cho task 2.6 |
| iss / aud | `SmartDrone` / `SmartDroneClients` |
| iat / exp / jti | thời điểm cấp, hết hạn, mã duy nhất |

Backend kiểm tra chữ ký, hạn dùng, issuer và audience.

**Refresh token** là chuỗi ngẫu nhiên 256 bit, không phải JWT. CSDL (bảng `refresh_tokens`) chỉ lưu SHA-256 của nó, nên lộ database cũng không dùng lại được token.

**Xoay vòng (rotation):** mỗi lần `/refresh`, token cũ bị thu hồi và cấp token mới.
Nếu một token đã thu hồi bị gửi lại (nghi bị đánh cắp), backend thu hồi **mọi** refresh token của người dùng đó và trả `INVALID_REFRESH_TOKEN`; người dùng phải đăng nhập lại.
Hệ quả cho client: chỉ gọi `/refresh` một lần tại một thời điểm (`apiClient.ts` đã gom các request song song thành một lần refresh).

Cấu hình trong `application.yml` (`app.jwt.*`). Khóa ký đặt bằng biến môi trường `APP_JWT_KEY` (≥ 32 ký tự); khóa mặc định chỉ dùng để chạy thử trên máy dev.

## 5. Luồng

1. Đăng ký: `POST /register` → `201 UserProfile`.
2. Đăng nhập: `POST /login` → web lưu `accessToken` và `refreshToken`.
3. Gọi API: gửi `Authorization: Bearer <accessToken>`.
4. Access token hết hạn → API trả `401 TOKEN_EXPIRED` → web gọi `/refresh` → lưu cặp token mới → gọi lại request ban đầu.
5. `/refresh` lỗi `401` → xóa token, chuyển về `/login`.
6. Đăng xuất: `POST /logout` với refreshToken → xóa token ở client. Endpoint này công khai vì refresh token chính là bằng chứng sở hữu và access token có thể đã hết hạn; token lạ hoặc đã thu hồi vẫn trả `204`.

## 6. Mã lỗi

Body lỗi (nội dung `application/problem+json`):

```json
{
  "type": "about:blank",
  "title": "Email hoặc mật khẩu không đúng",
  "status": 401,
  "detail": "Email hoặc mật khẩu không đúng",
  "code": "INVALID_CREDENTIALS",
  "message": "Email hoặc mật khẩu không đúng",
  "traceId": "7c4f1a52-3d7e-4e0b-9b1c-2a8e4d6f9a10"
}
```

`message` trùng `detail`, giữ lại để `apiClient.ts` hiện tại đọc được lỗi. Lỗi kiểm tra dữ liệu có thêm `errors: [{ "field": "...", "message": "..." }]`.

| HTTP | code | Khi nào | Endpoint |
|---|---|---|---|
| 400 | VALIDATION_ERROR | JSON sai, thiếu trường, vi phạm ràng buộc | cả 4 |
| 401 | INVALID_CREDENTIALS | sai email hoặc mật khẩu | login |
| 401 | INVALID_REFRESH_TOKEN | refresh token lạ, hết hạn hoặc đã dùng | refresh |
| 401 | AUTH_REQUIRED | thiếu hoặc sai access token | các API cần đăng nhập |
| 401 | TOKEN_EXPIRED | access token hết hạn | các API cần đăng nhập |
| 403 | ACCOUNT_LOCKED | tài khoản bị khóa | login, refresh |
| 403 | FORBIDDEN | đủ đăng nhập nhưng không đủ quyền | các API có phân quyền (2.6) |
| 409 | EMAIL_ALREADY_EXISTS | email đã đăng ký | register |
| 500 | INTERNAL_ERROR | lỗi hệ thống, không lộ chi tiết | mọi nơi |

## 7. Ví dụ

```bash
# Đăng ký
curl -X POST http://localhost:5000/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"an@example.com","password":"Passw0rdOK","fullName":"Nguyễn Văn An","phone":"0901234567"}'

# Đăng nhập
curl -X POST http://localhost:5000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"an@example.com","password":"Passw0rdOK"}'

# Làm mới token
curl -X POST http://localhost:5000/api/v1/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"<refreshToken nhận được khi đăng nhập>"}'

# Đăng xuất
curl -X POST http://localhost:5000/api/v1/auth/logout \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"<refreshToken>"}'
```

Swagger UI: `http://localhost:5000/api/swagger-ui.html`.

## 8. Khác với APDDMP-9 — cần nhóm đồng bộ

1. `TokenResponse` có thêm trường `user` (APDDMP-9 chỉ ghi `Tokens`). Thêm trường nên không làm vỡ client nào bám theo bản cũ.
2. `POST /auth/logout` công khai, không bắt access token (APDDMP-9 ghi vai trò ALL).
3. Thêm 4 mã lỗi: `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN`, `EMAIL_ALREADY_EXISTS`, `ACCOUNT_LOCKED`.
4. Body lỗi có thêm `message` cho web.
5. Chưa có giới hạn tần suất (`429 RATE_LIMITED`) và khóa tạm sau nhiều lần đăng nhập sai. Cần thêm cột đếm lần sai vào bảng `users`; ERD APDDMP-8 có `failedLoginCount`, `lockedAt` nhưng entity `User` của task 2.1 chưa có.
6. Entity `User` của task 2.1 (`email`, một `role`) khác ERD APDDMP-8 (`username`, bảng `UserRoles` nhiều–nhiều). Task 2.5 bám code 2.1; cần chốt lại với người phụ trách ERD trước khi sang 2.6.
7. Chưa có job dọn refresh token hết hạn trong bảng `refresh_tokens`.
