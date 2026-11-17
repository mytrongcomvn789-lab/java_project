# Kiểm thử tích hợp và bản sửa ngày 16–17/11/2026

- Mã nguồn gốc: `660e1d59c7721122188342f3d3cdb9679f029d93` (main, merge PR #24).
- Nhánh sửa local: `fix/integration-2026-11-16`.
- Môi trường backend: Windows, Temurin JDK 25.0.4.1, Maven; CSDL thử nghiệm H2 ở chế độ MSSQLServer. Web: TypeScript/Vite và Chrome. Mobile: Flutter 3.50.0 / Dart 3.14.0.
- Test backend, build web và trình duyệt chạy ngày 16/11; hoàn tất CI/tài liệu ngày 17/11, giờ Việt Nam.

## Các lỗi và thay đổi

| Vấn đề đã kiểm chứng | Nguyên nhân | Thay đổi | Kết quả |
|---|---|---|---|
| GET `/api/orders?sort=,` hoặc `sort=,,` trả 500 | Java split bỏ phần rỗng, truy cập phần tử không tồn tại | `OrderListQuery.sort()` giữ phần rỗng bằng `split(",", -1)` rồi kiểm tra tên trường/hướng | Trả 400 / VALIDATION_ERROR |
| `sort=createdAt,` được chấp nhận dù thiếu hướng | Phần rỗng cuối bị bỏ | Cùng bản sửa parser | Trả 400 / VALIDATION_ERROR |
| `page=2147483647&pageSize=100` trả 500 | Offset vượt giới hạn integer của JPA | Tính offset bằng long và chặn trước truy vấn | Trả 400 / VALIDATION_ERROR |
| Maven trên máy Windows/JDK 25 dừng trước khi chạy test | Tiến trình Surefire fork không trả kết quả; có log lỗi đường dẫn khác ổ đĩa | Profile chỉ áp dụng Windows/JDK 25: chạy test trong JVM Maven (`forkCount=0`) | Lệnh `mvn -B clean verify` chạy đủ suite. Đây là workaround môi trường, không khẳng định sửa gốc JVM. Java 21/Linux giữ cấu hình fork mặc định |
| Widget test vẫn kiểm tra nút cộng/counter của template, không bọc Riverpod | Test không khớp ứng dụng thực tế | Bọc `MyApp` trong `ProviderScope`, xác nhận Home và không có exception | Widget test đạt |
| GitHub mobile-ci chỉ checkout nhưng vẫn báo xanh | Chưa có bước analyze/test | Cài SDK cố định, kiểm lockfile, analyze và widget test | Các lệnh được kiểm chứng local; GitHub Actions cần chạy sau khi push |
| Tài liệu sửa/hủy đơn trỏ đến file không tồn tại | Thiếu báo cáo 3.6 | Liên kết đến báo cáo này | Có tài liệu kiểm chứng để đọc |

`mobile/pubspec.lock` được cập nhật theo các dependency SDK cố định. Không nâng major Riverpod/GoRouter/Dio. `mobile/README.md` ghi rõ phiên bản và phạm vi test.

## Kết quả backend và web

| Kiểm tra | Kết quả |
|---|---|
| Backend `mvn -B clean verify` | **386 tổng: 377 đạt, 9 bỏ qua, 0 failure, 0 error; BUILD SUCCESS** |
| HTTP regression mới trong `OrderIntegrationTest` | 4 đầu vào sai đều trả 400 và mã VALIDATION_ERROR |
| Web `npm ci --no-audit --no-fund` | Thành công |
| Web `npm run build` (TypeScript + Vite) | Thành công |
| Bộ kiểm tra trực tiếp bằng Playwright/Chrome | **8/8 đạt**, không có unhandled JavaScript error |
| Flutter kiểm lockfile | Thành công với `--enforce-lockfile` |
| Flutter analyze | 0 error, 0 warning, 2 info deprecated; exit 0 khi dùng `--no-fatal-infos` |
| Flutter widget test | **1/1 đạt** |

### Các luồng kiểm tra trực tiếp

1. Tạo đơn bằng form thật (địa chỉ, trạm, kiện hàng), mở chi tiết.
2. Sửa đơn PENDING, thay khối lượng/nội dung, tải lại xác nhận dữ liệu lưu đúng.
3. Hủy: đóng hộp xác nhận không làm hủy đơn; xác nhận hủy rồi tải lại thấy CANCELLED.
4. Khách đang sửa trong lúc điều phối viên duyệt: yêu cầu sửa cũ bị từ chối 412, tải lại đúng trạng thái; không ghi đè quyết định duyệt.
5. Điều phối viên từ chối: khách xem được lý do.
6. Khách A không được mở trang sửa đơn của khách B.
7. Bốn đầu vào API sai nêu trên trả 400 sau sửa.
8. Không phát sinh JavaScript exception chưa xử lý trong bộ luồng đã chạy.

## Lệnh kiểm tra lại

```powershell
# backend
mvn -B clean verify

# web
npm ci --no-audit --no-fund
npm run build

# mobile, Flutter 3.50.0
flutter pub get --enforce-lockfile
flutter analyze --no-pub --no-fatal-infos
flutter test --no-pub
```

Regression backend: `backend/src/test/java/com/smartdrone/api/orders/OrderIntegrationTest.java`, test `malformedSortAndExcessivePaginationReturn400InsteadOf500`.

## Giới hạn cần hiểu đúng

- 7 test `MinioFileFlowTest` bỏ qua vì chưa có cấu hình MinIO local.
- 2 test cạnh tranh cập nhật/tạo đơn trong `OrderIntegrationTest` bỏ qua vì thiếu `ORDER_TEST_DATABASE_URL`. Các test duyệt đơn khác vẫn chạy; không được tính hai test bỏ qua là đã đạt.
- H2 chế độ MSSQLServer không thay thế kiểm thử SQL Server thật.
- Mobile hiện chỉ có khung Home: chưa kiểm thử tính năng đặt đơn trên Android/iOS, chưa build APK/IPA.
- Hai thông tin deprecated về `package:flutter/material.dart` còn hiển thị trên Flutter 3.50. Dùng `--no-fatal-infos` để không coi mức info là lỗi; warnings/errors vẫn làm CI thất bại. Không báo analyze sạch hoàn toàn.
- Nền tảng scheduling mới merge không có nghĩa mọi tính năng giai đoạn 4–6 đã hoàn tất; bản sửa này không tự triển khai thêm phần việc của thành viên khác.
- Kết quả trên áp dụng đúng commit gốc và bản sửa local này, không bảo đảm mọi trường hợp nghiệp vụ đều không có lỗi.

## Bằng chứng

Xem các file JSON tại [integration-2026-11-16-evidence](integration-2026-11-16-evidence/): danh sách suite backend, kết quả kiểm tra trình duyệt, kết quả HTTP regression và kết quả Flutter.

Log đầy đủ và ảnh kiểm chứng trên máy: `C:\Users\Cong Nha\Documents\Codex\QA-2026-11-16` (backend-final.log, web-build.log, browser-regression.log, edit-conflict.png và các log flutter-final-*).
