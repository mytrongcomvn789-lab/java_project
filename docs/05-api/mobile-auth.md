# Mobile 6.1: đăng nhập, hồ sơ, phân vai trò

Nhánh gốc: `feature/6.1-mobile-auth` (đã gộp vào `main`). Backend không đổi: mobile dùng API xác thực sẵn có (xem [auth-api.md](auth-api.md)).
Tài liệu này là bàn giao của 6.1 cho 6.2 đến 6.5: API client dùng chung, phiên đăng nhập, router và cấu hình địa chỉ máy chủ.

## 1. API dùng

| API | Dùng để |
|---|---|
| `POST /v1/auth/login` | Đăng nhập, nhận cặp `accessToken` + `refreshToken` và hồ sơ |
| `POST /v1/auth/refresh` | Đổi cặp token (refresh token được xoay vòng, token cũ không dùng lại được) |
| `POST /v1/auth/logout` | Thu hồi refresh token phía server |
| `GET /v1/me`, `PATCH /v1/me` | Đọc và sửa hồ sơ (chỉ `fullName`, `phone`) |

`email` và vai trò là dữ liệu của server: app không cho sửa và không tin vào bản lưu cục bộ.

## 2. Thành phần dùng chung (cho 6.2 đến 6.5)

| Thành phần | Tệp | Vai trò |
|---|---|---|
| `createDio()` | `lib/core/network/dio_client.dart` | Tạo `Dio` với `baseUrl`, timeout 15/20/20 giây, header JSON |
| `Session` | `lib/features/auth/session.dart` | Giữ token, hồ sơ và pha phiên; gắn `Authorization` vào mọi request; tự làm mới khi gặp 401 |
| `sessionProvider` | `lib/features/auth/session.dart` | Provider Riverpod của `Session`; các tính năng lấy Dio có JWT qua `ref.read(sessionProvider).api` |
| `TokenStore` / `SecureTokenStore` | `lib/core/storage/storage_service.dart` | Lưu token bằng `flutter_secure_storage`, một khoá duy nhất `smartdrone.session.v1` (ghi cả cặp, không bao giờ ghi nửa chừng) |
| `routerProvider`, `sessionRedirect` | `lib/core/router/app_router.dart` | Điều hướng theo pha phiên và vai trò |
| `toApiFailure`, `guardApi` | `lib/core/network/api_failure.dart` | Chuyển lỗi mạng/HTTP thành `ApiFailure` có `FailureKind` và thông báo tiếng Việt |

Mọi tính năng sau dùng `Dio` của phiên này. `authApi` là một `Dio` riêng không có interceptor, chỉ dùng cho login, refresh và logout để tránh vòng lặp làm mới.

## 3. Hành vi của phiên

| Tình huống | Hành vi |
|---|---|
| Mở app, chưa có token | Về màn đăng nhập, không gọi API |
| Mở app, có token | Gọi `GET /v1/me` để lấy hồ sơ từ server (không tin vai trò đã lưu) |
| Access token hết hạn (401) | Làm mới **một lần**, ghi cặp token mới, gửi lại request. Nhiều request 401 cùng lúc **dùng chung một lần làm mới** |
| Làm mới vẫn 401 | Xoá token, về màn đăng nhập |
| Request đã thử lại mà vẫn 401 | Không thử lại lần nữa; coi phiên không hợp lệ và đăng xuất |
| 403 | **Không** làm mới và **không** đăng xuất (đã đăng nhập nhưng thiếu quyền) |
| Mất mạng khi khôi phục hoặc làm mới | Giữ nguyên token, hiện màn "Không kết nối được máy chủ" với nút **Thử lại**; mất mạng không phải bằng chứng token sai |
| Đăng xuất | Gọi thu hồi phía server; **dù thu hồi lỗi vẫn xoá token cục bộ** và báo cho người dùng |
| Phản hồi làm mới đến muộn sau khi đăng xuất | Bị bỏ nhờ bộ đếm phiên (`epoch`), không làm phiên sống lại |
| Đổi tài khoản | Hồ sơ và token của người trước bị xoá; hàng đợi offline (6.5) và thông báo (6.3) tách theo tài khoản |

## 4. Điều hướng theo vai trò

| Vai trò | Màn chính | Ghi chú |
|---|---|---|
| `CUSTOMER` | `/customer` | Trang chủ khách |
| `STATION_OPERATOR` | `/operator` | Trang vận hành |
| Vai trò khác | `/unsupported` | Màn "Vai trò chưa được hỗ trợ" kèm nút Đăng xuất |

`/profile` dùng chung cho cả hai vai trò. Vào route của vai trò khác bị chuyển về màn chính của mình. Việc chặn này chỉ là tiện lợi: **quyền thật do backend quyết định** (RBAC trả 403), nút ẩn trên giao diện không phải biện pháp bảo vệ.

## 5. Cấu hình địa chỉ máy chủ

Mặc định `http://10.0.2.2:5000/api` (địa chỉ máy tính nhìn từ **emulator Android**). Đổi bằng `--dart-define`:

| Chạy trên | Lệnh |
|---|---|
| Emulator Android | `flutter run -d emulator-5554` (dùng mặc định) |
| Điện thoại thật cùng Wi-Fi | `flutter run -d <id> --dart-define=API_BASE_URL=http://<IP-máy-tính>:5000/api` |

Lưu ý khi chạy emulator: MinIO cũng phải được cấu hình địa chỉ công khai là `10.0.2.2` (xem [mobile-customer-orders.md](mobile-customer-orders.md) mục hướng dẫn demo) để ảnh tải lên được. Backend dùng chung tài khoản demo `*@demo.local` với mật khẩu `Demo@12345` khi chạy profile `demo`.

## 6. Kiểm thử

```powershell
cd mobile
flutter analyze --no-pub --no-fatal-infos
flutter test --no-pub
```

Kết quả trên nhánh `feature/6.1-mobile-auth` ngày 27/11/2026: `flutter analyze` 0 lỗi (còn 1 `info` có sẵn ở `test/session_test.dart:160`); `flutter test` **19 test đều qua**.

`test/session_test.dart` (16 ca) gồm: kho rỗng không gọi API; đăng nhập lưu đủ hai token và đăng xuất xoá chúng; sai mật khẩu không lưu phiên; khôi phục lấy hồ sơ từ server chứ không tin bản lưu; làm mới một lần và thử lại; nhiều 401 cùng lúc chỉ làm mới một lần; làm mới thất bại xoá phiên; 401 thử lại không lặp vô hạn; 403 không làm mới; mất mạng khi khôi phục giữ token; lỗi mạng khi làm mới không ép đăng xuất; phản hồi làm mới muộn sau đăng xuất không hồi sinh phiên; đổi tài khoản xoá dữ liệu cũ; cập nhật hồ sơ chỉ gửi trường được sửa; vai trò không hỗ trợ vào màn riêng; thu hồi thất bại vẫn xoá token cục bộ và báo người dùng.

`test/widget_test.dart` (4 ca) kiểm tra màn đăng nhập: kiểm tra nhập trước khi gửi; chặn bấm đúp khi đang gửi; thông báo sai mật khẩu và cho thử lại; hình nền không đè lên logo khi bàn phím mở.

## 7. Kiểm chứng thủ công

Chạy trên emulator Android 37 (`Medium_Phone_API_37.0`) với backend Spring Boot thật (profile `sqlserver,demo`), ngày 27/11/2026:

| Ca | Kết quả |
|---|---|
| Đăng nhập khách hàng `customer@demo.local` | Vào trang chủ khách, hiển thị đơn thật |
| Đăng nhập nhân viên trạm `operator@demo.local` | Vào trang vận hành, danh sách đơn theo trạm được phân công |
| Backend tắt khi mở app | Hiện "Không kết nối được máy chủ" với nút **Thử lại** (đã quan sát) |

**Chưa kiểm chứng trên thiết bị** (mới có unit/widget test): màn "Vai trò chưa được hỗ trợ" với tài khoản điều phối viên, quản lý, quản trị; hồi phục sau khi bật lại backend; làm mới token khi access token hết hạn thật; sửa hồ sơ. Cũng chưa test điện thoại Android thật và iOS (repo chưa có thư mục iOS).
