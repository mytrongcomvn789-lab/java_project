> Cập nhật 7.4 (25/11/2026): hợp đồng CHAT đã được triển khai và thay thế phần chat nháp bên dưới. Xem [customer-ai-chat.md](customer-ai-chat.md): chỉ CUSTOMER, hội thoại theo chủ sở hữu, fallback chat có result. ETA/summary/analytics không thay đổi.

# AI API (task 7.1, APDDMP-51) — hợp đồng NHÁP v1

> **Chờ chốt với chủ 7.2–7.6.** Task 7.1 chỉ dựng khung: endpoint trả đúng envelope này nhưng **chưa có logic ETA/tóm tắt/chat/phân tích thật**. Schema `result` của từng tác vụ có thể đổi khi các task đó làm.
> Kiến trúc và mối đe dọa: `docs/03-architecture/ai-service.md`.

## 1. Quy ước chung

- Base: `/api/v1/ai/**`. Chỉ nhận **JWT người dùng** (`Authorization: Bearer …`). API key 5.4 (`X-Api-Key`) **không** dùng được: request không có JWT bị `401 AUTH_REQUIRED`.
- Quyền khai báo ở `RbacPolicy` (URL) và `@PreAuthorize` (controller/service); ma trận kiểm thử ở `rbac-matrix.csv`.
- Phản hồi luôn kèm `Cache-Control: no-store`.
- Đơn ngoài phạm vi của người gọi và đơn không tồn tại cùng trả `404 RESOURCE_NOT_FOUND` (quy ước của `GET /v1/orders/{id}`); provider **không** được gọi.

### Envelope phản hồi (mọi endpoint)

| Trường | Ý nghĩa |
|---|---|
| `result` | Đối tượng theo tác vụ (mục 3); `null` khi `degraded=true` |
| `model` | Tên model đã dùng (`mock` với Mock; `null` khi fallback) |
| `mock` | `true` nếu từ MockAiProvider: **không phải kết quả AI thật** |
| `degraded` | `true` nếu là fallback: **không phải kết quả AI thật** |
| `source` | `PROVIDER` \| `MOCK` \| `FALLBACK` |
| `usage` | `{promptTokens, outputTokens, totalTokens}` |
| `requestId` | UUID để đối chiếu log/audit |
| `generatedAt` | Thời điểm tạo (UTC, ISO-8601) |
| `fallbackReason` | Mã lỗi gốc khi fallback, ngược lại `null` |

## 2. Bảng quyền

| Endpoint | CUSTOMER | DISPATCHER | STATION_OPERATOR | LOGISTICS_MANAGER | SYSTEM_ADMIN |
|---|---|---|---|---|---|
| `GET /v1/ai/orders/{id}/eta` | đơn của mình | đơn thuộc trạm được phân công | như DISPATCHER | 403 | 403 |
| `POST /v1/ai/orders/{id}/summary` | đơn của mình | theo trạm | theo trạm | 403 | 403 |
| `POST /v1/ai/chat` | có | có | có | 403 | 403 |
| `POST /v1/ai/analytics` | 403 | 403 | 403 | có (số liệu tổng hợp) | 403 |

Ghi chú: `GET /v1/orders/{id}/eta` và `/summary` (không có `ai`) là route nghiệp vụ khác trong `RbacPolicy`, không thuộc task này.

## 3. Endpoint

### 3.1 ETA — `GET /v1/ai/orders/{id}/eta` (7.2, ĐÃ TRIỂN KHAI, thay hợp đồng nháp)

ETA tính bằng **công thức**, không gọi AI/provider: `ETA = mốc xuất phát + ceil(khoảng cách haversine ÷ vận tốc × 60) + thời gian chờ trạm`. `source = FORMULA`, `model = null`, `usage = 0`. Đây là ước tính nghiệp vụ, **không phải ML** và không điều khiển drone. Vận tốc/thời gian chờ lấy từ `app.eta.*` (giá trị hiện tại là giả định demo chưa hiệu chuẩn).

- SCHEDULED/PREPARING: mốc xuất phát = `slotStart` của lần giao ACTIVE (chưa có thì `now`; quá khứ thì chặn về `now`).
- IN_TRANSIT: mốc = thời điểm cất cánh trong lịch sử trạng thái + thời gian bay (không cộng chờ trạm). Quá hạn ước tính thì `etaAt = now`, kèm `OVERDUE_NO_LIVE_POSITION` (chưa có GPS drone thật).
- Trạng thái khác: `applicable=false`, `reason=STATUS_NOT_APPLICABLE`.

`result` khi tính được:

```json
{ "orderId": "...", "status": "SCHEDULED", "applicable": true, "computed": true,
  "method": "FORMULA_V1", "computedAt": "2026-11-25T03:00:00Z",
  "etaAt": "2026-11-25T05:08:00Z", "etaMinutes": 128,
  "travelMinutes": 3, "waitMinutes": 5, "distanceKm": 1.57,
  "confidence": "LOW", "limitations": ["STRAIGHT_LINE_DISTANCE", "CONSTANT_SPEED_UNCALIBRATED", "CONSTANT_STATION_WAIT"] }
```

Không tính được: `computed=false`, `reason` là `COORDINATES_MISSING` | `SPEED_INVALID` | `WAIT_INVALID`, `etaAt`/`etaMinutes` = null. `confidence` luôn `LOW` (không phải xác suất). Quyền: như bảng mục 2 (đơn ngoài phạm vi và không tồn tại → 404).

### 3.2 Tóm tắt đơn — `POST /v1/ai/orders/{id}/summary` (7.3)

Không có body. `result`: `{ "summary": "…", "highlights": ["…"] }`.

### 3.3 Chat — `POST /v1/ai/chat` (7.4)

```json
{ "conversationId": "optional-uuid", "message": "Đơn của tôi đến đâu rồi?", "orderId": "optional-uuid" }
```

- `message`: bắt buộc, không rỗng, tối đa `smartdrone.ai.max-chat-message-chars` (mặc định 1000) ký tự, vượt thì `400 VALIDATION_ERROR`.
- `orderId` (tùy chọn): phải thuộc quyền người gọi, nếu không `404`.
- `result`: `{ "reply": "…", "conversationId": "uuid" }`. Server **chưa lưu** hội thoại; `conversationId` chỉ là mã tương quan. Nơi lưu do 7.4 quyết định.

### 3.4 Phân tích vận hành — `POST /v1/ai/analytics` (7.5/7.6)

Chỉ LOGISTICS_MANAGER. Đầu vào là **bộ lọc thống kê 5.1**, không phải dữ liệu thô:

```json
{ "from": "2026-11-01", "to": "2026-11-24", "dateBasis": "CREATED", "stationId": null }
```

Tất cả trường tùy chọn (mặc định như `GET /v1/statistics/orders/summary`). Phạm vi trạm do `StatisticsService` áp dụng. `result`: `{ "insights": ["…"], "recommendations": ["…"] }`.

## 4. Fallback (khi `smartdrone.ai.fallback-enabled=true`)

Chỉ khi provider không cấu hình / lỗi / timeout / trả sai schema. Không bao giờ che lỗi quyền, rate limit hay ngân sách.

```json
{ "result": null, "model": null, "mock": false, "degraded": true, "source": "FALLBACK",
  "usage": { "promptTokens": 0, "outputTokens": 0, "totalTokens": 0 },
  "requestId": "…", "generatedAt": "…", "fallbackReason": "AI_TIMEOUT" }
```

## 5. Mã lỗi

Cùng khuôn lỗi chuẩn (`type,title,status,detail,code,message,traceId`).

| Mã | HTTP | Khi nào |
|---|---|---|
| `AUTH_REQUIRED` | 401 | Không có/sai JWT (kể cả chỉ gửi `X-Api-Key`) |
| `FORBIDDEN` | 403 | Vai trò không được dùng tác vụ này |
| `RESOURCE_NOT_FOUND` | 404 | Đơn không tồn tại hoặc ngoài phạm vi |
| `VALIDATION_ERROR` | 400 | Body sai (vd tin nhắn rỗng/quá dài) |
| `AI_NOT_CONFIGURED` | 503 | Chưa có khóa/AI tắt, hoặc nhà cung cấp từ chối khóa (401/403) |
| `AI_PROVIDER_UNAVAILABLE` | 503 | Nhà cung cấp lỗi 5xx/mạng, ngắt mạch đang mở, hoặc lỗi 400/404 |
| `AI_TIMEOUT` | 504 | Hết thời gian chờ |
| `AI_RATE_LIMITED` | 429 | Vượt tần suất của người dùng/toàn hệ thống, hoặc Gemini trả 429 |
| `AI_BUDGET_EXCEEDED` | 429 | Hết ngân sách token trong ngày |
| `AI_RESPONSE_INVALID` | 502 | Bị chặn an toàn, hoặc JSON sai schema |

Ví dụ lỗi:

```json
{ "type": "about:blank", "title": "Dịch vụ AI phản hồi quá chậm, vui lòng thử lại sau", "status": 504,
  "detail": "Dịch vụ AI phản hồi quá chậm, vui lòng thử lại sau", "code": "AI_TIMEOUT",
  "message": "Dịch vụ AI phản hồi quá chậm, vui lòng thử lại sau", "traceId": "…" }
```

Không có chi tiết nhà cung cấp trong lỗi.
