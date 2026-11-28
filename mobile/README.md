# SmartDroneDelivery mobile

Ứng dụng Flutter (Riverpod, Dio, go_router, flutter_secure_storage, mobile_scanner) cho hai vai trò:

| Vai trò | Chức năng |
|---|---|
| **Khách hàng** (`CUSTOMER`) | Trang chủ, tạo/xem/hủy đơn, theo dõi đơn thời gian thực (bản đồ, ETA, mã nhận hàng), thông báo, trợ lý AI, sổ địa chỉ, hồ sơ |
| **Nhân viên trạm** (`STATION_OPERATOR`) | Nhận kiện, chụp ảnh và xác nhận giao bằng OTP/QR, nhận hàng hoàn, hàng đợi offline tự đồng bộ |

Điều phối viên (`DISPATCHER`) cũng dùng được trên mobile. Vai trò khác (quản lý, quản trị) vào màn "Vai trò chưa được hỗ trợ" và dùng ứng dụng web.
Quyền luôn do backend quyết định; ẩn nút trên giao diện không phải biện pháp bảo vệ.

## Chạy ứng dụng

Cần backend đang chạy (xem README gốc). Với profile `demo` có sẵn tài khoản `customer@demo.local`, `operator@demo.local`, mật khẩu `Demo@12345`.

```powershell
cd mobile
flutter pub get
flutter run -d emulator-5554
```

Mặc định app gọi `http://10.0.2.2:5000/api` (địa chỉ máy tính nhìn từ emulator Android). Với điện thoại thật cùng Wi-Fi:

```powershell
flutter run -d <id> --dart-define=API_BASE_URL=http://<IP-máy-tính>:5000/api
```

Nếu build Android báo `Could not close incremental caches` (Kotlin), thêm vào `~/.gradle/gradle.properties`:
`kotlin.incremental=false` và `kotlin.compiler.execution.strategy=in-process`.

## Cấu trúc

```
lib/
  core/        mạng (Dio, lỗi), theme và widget dùng chung, router, realtime (STOMP), lưu trữ an toàn, hàng đợi offline
  features/
    auth/        đăng nhập, phiên, hồ sơ                (6.1)
    orders/      đơn, theo dõi, tạo đơn, trang chủ khách (6.2, 6.3)
    notifications/ thông báo trong app                   (6.3)
    operator/    nhận kiện, xác nhận giao, quét QR, hoàn hàng, đồng bộ (6.4, 6.5)
    ai/, assistant/ ETA, tóm tắt, chat AI                (7.6)
    flight/      số đo drone (dữ liệu mẫu có nhãn Demo cho tới khi backend có API)
```

Màn hình dùng dữ liệu mẫu luôn có nhãn **Demo** hoặc nút **Sắp có**, không giả làm dữ liệu thật. Danh sách API backend còn thiếu: [docs/05-api/mobile-ui-required-apis.md](../docs/05-api/mobile-ui-required-apis.md).

## Tài liệu theo task

| Task | Tài liệu |
|---|---|
| 6.1 Đăng nhập, hồ sơ, phân vai trò | [mobile-auth.md](../docs/05-api/mobile-auth.md) |
| 6.2 Khách hàng: đơn hàng | [mobile-customer-orders.md](../docs/05-api/mobile-customer-orders.md) |
| 6.3 Realtime và thông báo | [mobile-realtime-tracking.md](../docs/05-api/mobile-realtime-tracking.md) |
| 6.4 Vận hành: nhận kiện, ảnh, QR | [mobile-operator.md](../docs/05-api/mobile-operator.md) |
| 6.5 Thất bại, đồng bộ offline | [mobile-offline-sync.md](../docs/05-api/mobile-offline-sync.md) |

## Kiểm tra giống CI

SDK đã kiểm chứng: Flutter **3.50.0**, Dart **3.14.0**. CI cố định SDK revision `ab2912035e160d9cad6306df307576da6128ebdb`.

Chạy trong thư mục `mobile`:

```sh
flutter pub get --enforce-lockfile
flutter analyze --no-pub --no-fatal-infos
flutter test --no-pub
```

Flutter 3.50 báo hai thông tin deprecated ở import `package:flutter/material.dart`. CI vẫn in các thông tin này, nhưng chỉ lỗi và cảnh báo làm bước analyze thất bại. Chạy `flutter analyze --no-pub` nếu muốn cả thông tin cũng làm lệnh thất bại.

Test phải bọc `MyApp` bằng `ProviderScope`, giống cách `main()` khởi tạo ứng dụng. Test dùng khung điện thoại 390 × 844 dp theo đặc tả giao diện.

Chưa kiểm chứng trên điện thoại Android thật và iOS (repo chưa có thư mục iOS).
