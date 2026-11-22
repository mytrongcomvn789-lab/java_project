# Task 2.4: Audit log, lỗi chuẩn và traceId

Đáp ứng NFR-07 (SRS): ghi nhận và truy vết các thao tác quan trọng.

## Tra cứu audit log

`GET /api/v1/audit-events`, chỉ `SYSTEM_ADMIN` (vai trò khác: 403, chưa đăng nhập: 401).

| Tham số | Kiểu | Mặc định | Ghi chú |
|---|---|---|---|
| `page` | int | 1 | từ 1 |
| `pageSize` | int | 20 | 1 đến 100 |
| `action` | string | | vd `LOGIN_FAILED` |
| `actorId` | UUID | | người thực hiện |
| `targetType` | string | | `USER`, `STATION`, `ROLE`, `SETTINGS` |
| `targetId` | string | | id đối tượng |
| `from`, `to` | ISO-8601 | | khoảng `[from, to)`, vd `2026-11-22T00:00:00Z`; `from` phải trước `to` |

Kết quả sắp xếp mới nhất trước, header `Cache-Control: no-store`:

```json
{
  "items": [{
    "id": "…", "action": "LOGIN_FAILED",
    "actorId": "…", "actorEmail": "a@b.vn", "actorRole": "CUSTOMER",
    "targetType": "USER", "targetId": "…",
    "detail": "reason=INVALID_CREDENTIALS, email=a@b.vn",
    "ipAddress": "127.0.0.1", "traceId": "35c6f7d1-…",
    "occurredAt": "2026-11-22T15:18:03Z"
  }],
  "page": 1, "pageSize": 20, "total": 1
}
```

## Thao tác được ghi

| action | Khi nào | targetType |
|---|---|---|
| `USER_REGISTERED` | đăng ký tài khoản | USER |
| `LOGIN_SUCCEEDED` | đăng nhập thành công | USER |
| `LOGIN_FAILED` | sai email/mật khẩu (`reason=INVALID_CREDENTIALS`) hoặc tài khoản bị khóa (`reason=ACCOUNT_LOCKED`). Email không tồn tại thì `actorId` rỗng | USER |
| `LOGOUT` | đăng xuất (gửi lại token đã thu hồi thì không ghi thêm) | USER |
| `REFRESH_TOKEN_REUSED` | refresh token đã dùng bị gửi lại, mọi phiên bị thu hồi | USER |
| `PASSWORD_CHANGED` | người dùng đổi mật khẩu | USER |
| `USER_ACCESS_CHANGED` | admin đổi vai trò / khóa tài khoản qua `/v1/users/{id}/access` | USER |
| `ACCOUNT_CREATED`, `ACCOUNT_UPDATED`, `ACCOUNT_LOCKED`, `ACCOUNT_UNLOCKED` | quản trị tài khoản (FR-02) | USER |
| `ROLE_PERMISSIONS_UPDATED` | đổi quyền của vai trò (FR-02) | ROLE |
| `SETTINGS_UPDATED` | đổi cấu hình hệ thống (FR-02) | SETTINGS |
| `STATION_CREATED`, `STATION_UPDATED`, `STATION_STATUS_CHANGED` | quản lý trạm | STATION |

Đổi trạng thái đơn hàng đã có lịch sử riêng đầy đủ trong `order_status_history` (người làm, lý do, traceId), nên không ghi trùng vào audit log.

## Quy tắc khi thêm audit mới

Dùng `com.smartdrone.application.admin.AuditService`:

- `record(actorId, action, targetType, targetId, detail)`: ghi **cùng transaction** với thao tác. Thao tác rollback thì dòng audit cũng mất.
- `recordForCurrentUser(action, targetType, targetId, detail)`: như trên, người làm lấy từ tài khoản đang đăng nhập.
- `recordIndependently(...)`: transaction riêng, cho lần thử thất bại mà ngay sau đó ném lỗi (vd đăng nhập sai). Lỗi ghi audit không làm hỏng request.
- `detail` **không bao giờ** chứa mật khẩu, token, mã nhận hàng.
- IP (địa chỉ kết nối trực tiếp, không tin `X-Forwarded-For`) và `traceId` được tự điền.

## Lỗi chuẩn và traceId

- Mọi lỗi trả `application/problem+json` gồm `code`, `message`, `traceId`; lỗi validation có thêm `errors[]`.
- `traceId` trong body lỗi = header `X-Trace-Id` = `traceId` của dòng audit = `[traceId]` ở đầu mỗi dòng log của request đó.
- Log ghi ra console và `logs/smartdrone-backend.log` (đổi bằng biến `LOG_FILE`), xoay vòng theo ngày hoặc 10MB, giữ 14 ngày, tối đa 1GB.

Database có sẵn trên SQL Server: chạy `backend/scripts/2.4-audit-logs.sql`.
