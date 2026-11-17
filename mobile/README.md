# SmartDroneDelivery mobile

Hiện tại mobile là khung Flutter/Riverpod/GoRouter với màn hình Home. Widget test xác nhận ứng dụng khởi tạo được và hiển thị đúng nội dung; chưa đại diện cho luồng đặt/giao hàng trên điện thoại.

## Kiểm tra giống CI

SDK đã kiểm chứng: Flutter **3.50.0**, Dart **3.14.0**. CI cố định SDK revision `ab2912035e160d9cad6306df307576da6128ebdb`.

Chạy trong thư mục `mobile`:

```sh
flutter pub get --enforce-lockfile
flutter analyze --no-pub --no-fatal-infos
flutter test --no-pub
```

Flutter 3.50 báo hai thông tin deprecated ở import `package:flutter/material.dart`. CI vẫn in các thông tin này, nhưng chỉ lỗi và cảnh báo làm bước analyze thất bại. Chạy `flutter analyze --no-pub` nếu muốn cả thông tin cũng làm lệnh thất bại. Việc chuyển sang gói Material mới chưa thuộc bản sửa tích hợp này.

Test phải bọc `MyApp` bằng `ProviderScope`, giống cách `main()` khởi tạo ứng dụng. Test đếm số mặc định của Flutter không phù hợp với màn hình Home hiện có.

Repository hiện chưa có các thư mục nền tảng Android/iOS; CI này chưa build APK/IPA và chưa kiểm chứng thiết bị thật.
