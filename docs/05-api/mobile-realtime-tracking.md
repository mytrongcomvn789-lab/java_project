# 6.3 — Mobile theo dõi đơn realtime và thông báo foreground

Phạm vi: app Flutter (`mobile/`) dùng **cùng** backend Java Spring Boot, cùng WebSocket/STOMP của task 4.3 và kênh thông báo của 4.9. Không thêm SignalR/.NET, không đổi backend, không thêm endpoint.

## Hợp đồng API mà mobile dùng

| Mục | Giá trị |
|---|---|
| Kết nối | `ws(s)://{host}/api/ws`, khung STOMP `CONNECT` kèm `Authorization: Bearer {accessToken}`, `accept-version:1.2`, `heart-beat:10000,10000` |
| Kênh đơn | `/topic/orders/{orderId}`. Tin `ORDER_STATUS_CHANGED`: `schemaVersion, eventId, type, event, orderId, attemptId, fromStatus, status, version, occurredAt` |
| Kênh thông báo | `/topic/notifications/{userId}` (chỉ CUSTOMER, chỉ userId của mình). Tin `NOTIFICATION_CREATED` + `notification` giống phần tử của REST |
| Lỗi STOMP | khung `ERROR`, `message` = `AUTH_REQUIRED`, `TOKEN_EXPIRED` hoặc `FORBIDDEN` |
| REST tải bù | `GET /v1/orders`, `GET /v1/orders/{id}/tracking` (timeline theo `orderVersion`, mỗi `eventId` của timeline = `eventId` của tin realtime), `GET /v1/notifications`, `POST /v1/notifications/{id}/read` |

Quy ước dùng chung với web: `eventId` khử trùng, `version`/`orderVersion` sắp thứ tự, `orderId` định tuyến.

## Hành vi của client (`mobile/lib/core/realtime`, `mobile/lib/features/orders`, `mobile/lib/features/notifications`)

- **Subscribe đúng đơn/khách**: mỗi màn hình theo dõi chỉ đăng ký `/topic/orders/{orderId}` của đơn đang mở, cộng một kênh `/topic/notifications/{userId}` cho cả phiên. Rời màn hình thì gửi `UNSUBSCRIBE` (provider autoDispose).
- **Áp dụng event** (`tracking_state.dart`): chỉ nhận `version = hiện tại + 1`. Trùng `eventId` hoặc `version` ≤ hiện tại thì bỏ; thiếu một version thì tải lại REST; sai `orderId` thì bỏ. REST cũ hơn dữ liệu đã hiển thị không kéo màn hình lùi.
- **Tải bù**: sau mỗi RECEIPT của SUBSCRIBE (kể cả sau reconnect/resume) gọi REST tracking và notifications.
- **Pause/resume**: app xuống nền → `DISCONNECT`, giữ danh sách subscription; mở lại → nối ngay (không chờ backoff), subscribe lại, tải bù.
- **Mất mạng**: phát hiện qua đóng/lỗi socket và watchdog (30 giây không nhận gì kể cả heart-beat). Nối lại với backoff 1→30 giây có jitter; có banner "Mất kết nối trực tuyến", dữ liệu cũ vẫn hiển thị.
- **Token hết hạn**: backend đóng socket (1008) hoặc trả `TOKEN_EXPIRED`; client làm mới token qua `Session.refresh()` rồi nối lại. Refresh bị từ chối thì Session đăng xuất như cũ.
- **Logout/đổi tài khoản**: socket và mọi subscription bị hủy, trạng thái thông báo bị bỏ, snackbar cũ bị xóa.
- **Foreground notification**: tin `NOTIFICATION_CREATED` hiện banner (nút "Xem" mở đơn), tăng chấm chưa đọc; chỉ `POST .../read` khi người dùng bấm mới đánh dấu đã đọc. Khử trùng theo `eventId`.
- `FORBIDDEN` khi subscribe một kênh chỉ loại kênh đó, không lặp vô hạn.

## Môi trường đã test

| Thành phần | Chi tiết |
|---|---|
| Máy | Windows 11, Flutter 3.47.8 / Dart 3.13.7, JDK 27, Maven 3.9.9, Node 20 |
| Backend | `mvn spring-boot:run -Dspring-boot.run.profiles=h2` (Spring Boot 4.0.5) với H2 dạng file. Biến môi trường chỉ cho test: `SPRING_DATASOURCE_URL` (H2 file, `AUTO_SERVER=TRUE`), `APP_JWT_KEY`, `APP_JWT_ACCESS_TOKEN_MINUTES=1` (token sống 1 phút để thử hết hạn), `APP_REALTIME_EXPIRY_SWEEP_INTERVAL=PT5S`. **Không phải SQL Server/MinIO**: chưa test trên SQL Server của nhóm |
| Thiết bị | Android emulator `Medium_Phone_API_37.0` (Android 17, x86_64). **Chưa test thiết bị thật, chưa test iOS** |
| Công cụ test (không đưa vào repo) | Dữ liệu tạo qua REST thật (đăng ký, tạo trạm, đơn, duyệt, lên lịch, nhận kiện, dispatch, arrive). Một proxy WebSocket cục bộ nằm giữa emulator và backend để ghi khung `SUBSCRIBE/UNSUBSCRIBE`, nhân đôi/đảo thứ tự tin và cắt kết nối |

Build debug dùng: `--dart-define=API_BASE_URL=http://10.0.2.2:5000/api --dart-define=WS_URL=ws://10.0.2.2:5001/api/ws --dart-define=REALTIME_DIAG=true` (`WS_URL` mặc định suy ra từ `API_BASE_URL`; `REALTIME_DIAG` hiện dòng chẩn đoán, mặc định tắt).

## Kết quả kiểm thử

Tự động (`flutter analyze`, `flutter test --no-pub`): xem mục "Lệnh chạy". Gồm test khung STOMP, reducer (trùng/cũ/hụt/đảo/đơn khác), client (nối, route theo subscription, hủy không rò, mất mạng, watchdog, TOKEN_EXPIRED, pause/resume, FORBIDDEN, logout), controller theo dõi đơn và thông báo.

Thủ công trên emulator + backend thật:

| Ca | Kết quả |
|---|---|
| Hai tài khoản không nhận chéo | Khách A chỉ thấy đơn của A; đẩy trạng thái đơn của B trong lúc A đang xem: không có tin nào tới thiết bị (log proxy), màn hình/banner của A không đổi. Đăng nhập B chỉ có kênh `/topic/notifications/{userId của B}` |
| App nền rồi mở lại | Xuống nền: `DISCONNECT`. Trong lúc đó đơn đi v2→v3. Mở lại: nối lại, subscribe lại, timeline hiện đủ v3 (`catchup` tăng) |
| Mất mạng | Chặn kết nối ở proxy: banner mất kết nối, retry với backoff; đơn đi tiếp lên v4 trong lúc mất mạng; gỡ chặn: tự nối lại và timeline có v4. Lưu ý: chỉ WebSocket bị chặn, REST vẫn thông; sau khi gỡ chặn có thể chờ tới ~30 giây (backoff tối đa) |
| Token hết hạn | Token 1 phút: sau ~1 phút backend đóng phiên (1008), app làm mới token và nối lại trong khoảng 1 giây, không mất subscription |
| Event trùng / đảo thứ tự | Proxy nhân đôi tin v5: hiển thị một lần (`dup=1`). Proxy đảo v1/v2 của cùng một đơn: màn hình kết thúc đúng v2 với đủ 3 dòng timeline (`gap=1` → tải bù REST) |
| Không rò subscription | Chẩn đoán trong app và log proxy: vào đơn → `subs=2`; thoát → `UNSUBSCRIBE`, `subs=1`; sang đơn khác → đúng kênh đơn mới; đăng xuất → `DISCONNECT`, không còn socket |
| Lỗi tìm ra và đã sửa | Banner thông báo của A vẫn hiện sau khi đăng xuất và đăng nhập B. Đã xóa snackbar khi đổi người dùng và kiểm lại: B không thấy |

Chưa kiểm chứng trên thiết bị: ca "token bị từ chối ở CONNECT" (có unit test), tắt Wi-Fi thật của máy (chỉ mô phỏng bằng proxy).

## Hướng dẫn demo

1. Chạy backend (H2: `cd backend; mvn spring-boot:run "-Dspring-boot.run.profiles=h2"`; SQL Server theo README gốc). Emulator Android gọi máy chủ qua `10.0.2.2`.
2. Tạo dữ liệu: đăng ký hai khách, cấp vai trò điều phối/nhân viên trạm/quản lý hậu cần và phân công trạm theo hướng dẫn của 4.1/4.5, tạo trạm và đơn.
3. `cd mobile; flutter run --dart-define=API_BASE_URL=http://10.0.2.2:5000/api`.
4. Đăng nhập khách → mở một đơn → dùng web hoặc API (điều phối duyệt/lên lịch, nhân viên trạm nhận kiện/dispatch/arrive) và quan sát timeline, banner thông báo và chấm chưa đọc đổi theo thời gian thực.
5. Thử nền/mở lại, tắt mạng, đăng xuất/đăng nhập tài khoản khác.

## Chưa làm / giới hạn

- **Push khi app đóng hoặc ở nền (FCM/APNs): chưa làm.** Realtime chỉ hoạt động ở foreground; khi mở lại app tải bù qua REST và thấy thông báo trong danh sách. Cần quyết định nhóm: thêm `firebase_messaging`, bảng lưu device token ở backend, gửi từ `NotificationRecorder`, cấu hình khóa FCM/APNs.
- Màn hình danh sách/theo dõi đơn chỉ cho khách hàng; nhân viên trạm chưa có (thuộc task mobile khác). Chưa có màn hình tạo/hủy đơn (6.2).
- Chưa nối sự kiện kết nối mạng của hệ điều hành để bỏ qua backoff (`RealtimeClient.retryNow` đã có sẵn).
- Broker STOMP là bản trong bộ nhớ (một instance), theo ghi chú của 4.3/4.9.
- `mobile/pubspec.lock`: chỉ thêm `web_socket` và `web_socket_channel`; chưa chạy được `--enforce-lockfile` với Flutter 3.50 của CI vì máy test dùng Flutter 3.47.

## Lệnh chạy

```sh
cd mobile
flutter pub get
flutter analyze --no-pub
flutter test --no-pub
flutter build apk --debug
```
