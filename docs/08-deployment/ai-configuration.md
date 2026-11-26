# Cấu hình AI Service (task 7.1)

Không có secret trong file này; chỉ tên biến và giá trị ví dụ giả.

## Biến môi trường

| Biến | Bắt buộc | Ý nghĩa |
|---|---|---|
| `SMARTDRONE_AI_GEMINI_API_KEY` | Chỉ khi dùng Gemini thật | Khóa Gemini API (lấy ở Google AI Studio). **Không** đặt vào `application*.yml`, `.env` commit, URL hay log. |

Khóa này hoàn toàn tách biệt với API key 5.4 (khóa cấp cho hệ thống ngoài gọi vào `/v1/integration/**`).

## Thuộc tính `smartdrone.ai.*` (mặc định trong `application.yml`)

| Thuộc tính | Mặc định | Ghi chú |
|---|---|---|
| `provider` | `AUTO` | `AUTO` / `DISABLED` / `MOCK` / `GEMINI`. AUTO: có khóa → Gemini; không khóa → Mock (chỉ profile dev/test/h2/demo) hoặc Disabled |
| `fallback-enabled` | `false` | `true`: provider lỗi thì trả `degraded=true` thay vì lỗi 5xx |
| `send-personal-data` | `false` | Giữ `false` với gói miễn phí |
| `gemini.model` | `gemini-2.5-flash-lite` | **Cần kiểm tra lại**: Google đổi tên/ngừng model thường xuyên |
| `gemini.base-url` / `api-version` | `https://generativelanguage.googleapis.com` / `v1beta` | |
| `gemini.connect-timeout` / `read-timeout` | `PT3S` / `PT15S` | |
| `gemini.max-retries` | `2` | Tối đa 2 |
| `rate-limit.per-user-per-minute` / `global-per-minute` | `5` / `10` | Ước lượng, đối chiếu AI Studio |
| `budget.user-tokens-per-day` / `global-tokens-per-day` | `20000` / `200000` | Ước lượng, đối chiếu AI Studio |

## Chạy

PowerShell:

```powershell
$env:SMARTDRONE_AI_GEMINI_API_KEY = "<khóa-của-bạn>"
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=h2"
```

Không đặt biến: ở profile `h2` backend dùng Mock (kết quả có `mock=true`); ở profile khác `/v1/ai/**` trả `503 AI_NOT_CONFIGURED`.

Cảnh báo dữ liệu gói miễn phí: xem `docs/03-architecture/ai-service.md`, mục 6.
