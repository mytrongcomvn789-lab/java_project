# APDDMP-22 — Bàn giao và kiểm thử mục 3.1

Ngày: 09/11/2026. Nhánh bàn giao: feature/APDDMP-22-customer-addresses.
Đã fast-forward main từ ce373ec lên ebd313f trước khi triển khai; cây làm việc ban đầu sạch.
Giữ nguyên lịch phân công/deadline. Bàn giao trên nhánh riêng feature/APDDMP-22-customer-addresses; việc merge vào main thực hiện sau review.

## Kết quả đã kiểm chứng

| Hạng mục | Kết quả |
|---|---|
| Java compile và đóng gói JAR | PASS, Maven verify |
| Toàn bộ backend | 280 tests, 0 failures, 0 errors, 0 skipped |
| API địa chỉ mới | 40 tests, 0 failures/errors/skipped |
| RBAC và hồ sơ/đổi mật khẩu hiện có | 214 tests, 0 failures/errors |
| Auth service / JWT / lỗi / health / enum | 26 tests, 0 failures/errors |
| Web cài dependency theo package-lock | npm ci thành công |
| TypeScript và Vite production build | PASS |
| Chrome với backend thật, H2 | PASS: login, sửa hồ sơ, reload, CRUD địa chỉ, chọn địa chỉ |
| Giao diện 390px | Đã xem ảnh; menu ngang, form/thẻ đủ chiều rộng, không cuộn ngang toàn trang |
| git diff --check | PASS |

Môi trường thực chạy: JDK Temurin 25.0.4.1, Maven compiler release 21 theo pom; Spring Boot 4.0.5; H2 PostgreSQL mode.
Chưa kiểm thử trên PostgreSQL thật hoặc runtime JDK 21 trong lượt này.

## Lệnh kiểm tra đã chạy

```powershell
# Trong backend; dùng Maven sẵn có trên máy, JAVA_HOME trỏ JDK đã cài
mvn -B test "-Dtest=AddressIntegrationTest" "-DforkCount=0"
mvn -B verify "-DforkCount=0"

# Trong web
npm ci --no-audit --no-fund
npm run build
```

Lần chạy test fork mặc định gặp sự cố Surefire/JVM trên môi trường Windows (forked VM terminated), chưa cho kết quả test hợp lệ.
Đã chạy lại cùng các test với forkCount=0 và có kết quả PASS ở trên; không bỏ qua test, không đổi pom để tắt test.
Môi trường khác có thể chạy mvn test bình thường; máy này dùng lệnh đã kiểm chứng phía trên.

## Nội dung test địa chỉ

- CUSTOMER tạo → list → sửa → xóa; 201/200/204, Location và Cache-Control.
- Soft delete giữ dòng CSDL, không còn trong danh sách, không sửa/dùng lại địa chỉ đã xóa.
- Hai khách hàng: list không lộ dữ liệu; PUT/DELETE địa chỉ người khác trả 404.
- Bốn role còn lại bị từ chối cả 4 thao tác; anonymous trả 401.
- Giả customerId/userId/owner/id/deleted hoặc field lạ: trả 400, không tạo dữ liệu.
- Trường bắt buộc, độ dài, số điện thoại, khoảng tọa độ, page/pageSize sai: trả 400.
- Pagination theo chủ sở hữu; thứ tự và total đúng; trang ngoài phạm vi trả danh sách rỗng.
- Bản sao địa chỉ không đổi sau sửa/xóa; snapshot của địa chỉ người khác hoặc đã xóa bị từ chối.
- Service cũng có kiểm tra CUSTOMER, ngoài lớp filter/controller.

4 dòng address probe cũ trong ma trận RBAC được gỡ vì đã có controller thật.
Các tình huống 4 endpoint x 5 vai trò và anonymous được kiểm tra trong AddressIntegrationTest; không dùng controller giả để kết luận CRUD chạy được.

## Lỗi đã phát hiện và sửa khi thử giao diện

1. Regex điện thoại trong JSX của form hồ sơ bị escape dư: trình duyệt chặn số hợp lệ trước khi gửi API. Đã sửa và kiểm tra lưu/reload thành công.
2. Sidebar cũ chiếm quá nhiều chiều rộng trên điện thoại dù không có overflow. Đã chuyển menu thành thanh ngang ở màn hình <=700px và xem lại ảnh.
3. npm ci ban đầu bị khóa thư viện Rollup vì Vite đang chạy. Đã dừng Vite, cài lại đúng lockfile và build thành công; không sửa dependency để né lỗi.
4. README cũ còn lệnh dotnet run. Đã sửa sang Maven/Java 21 và thêm đường dẫn hướng dẫn.

## Bàn giao cho Luân

Xem ../05-api/addresses-api.md.
- Frontend AddressPicker(value, onChange) trả Address có id; chọn địa chỉ tại /deliveries/new hoạt động.
- Backend snapshotForOrder(customerIdFromJwt, addressId) trả DTO bất biến, kiểm tra ownership và địa chỉ chưa xóa.
- 3.6 phải lưu DTO vào addressSnapshot của đơn trong transaction. Code Order chưa có trên main nên chưa chứng minh lưu/đọc snapshot bằng đơn thật.
- Không gọi trang chọn địa chỉ là đã hoàn tất tạo đơn; nút gửi đơn và nghiệp vụ 3.6 chưa nằm trong thay đổi này.

## Cách chạy nhanh trên máy hiện tại

Mở Terminal PowerShell trong VS Code:

```powershell
cd D:\HOC\LT_JAVA\BTL\java_project\backend
$env:JAVA_HOME = 'D:\VSCODE\JAVA'
& 'D:\HOC\LT_JAVA\MAVEN\maven-mvnd-1.0.6-windows-amd64\mvn\bin\mvn.cmd' spring-boot:run '-Dspring-boot.run.profiles=h2'
```

Terminal thứ hai:

```powershell
cd D:\HOC\LT_JAVA\BTL\java_project\web
$env:VITE_API_URL = 'http://localhost:5000/api'
$env:VITE_DEMO_AUTH = 'false'
& 'D:\NODEJS\npm.cmd' run dev
```

Mở http://localhost:5000/api/swagger-ui/index.html để đăng ký một CUSTOMER qua POST /v1/auth/register.
Sau đó đăng nhập tại http://localhost:5173 bằng tài khoản vừa đăng ký.
Dùng menu Hồ sơ / Địa chỉ giao hàng. H2 mất dữ liệu khi tắt; dùng PostgreSQL khi cần lưu bền.
Trong kiểm tra tự động, cổng 5001/5174 được dùng riêng để tránh đụng dịch vụ đang mở.

## Bằng chứng

- APDDMP-22-evidence/backend-summary.json: thống kê từng suite.
- APDDMP-22-evidence/browser-result.json: kết quả browser smoke.
- APDDMP-22-evidence/addresses-desktop.png và addresses-mobile.png: ảnh dữ liệu thử, không phải dữ liệu người dùng thật.
