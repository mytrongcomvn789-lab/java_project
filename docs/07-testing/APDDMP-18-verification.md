# Báo cáo kiểm thử APDDMP-18 — RBAC Java

Ngày: 02/11/2026. Nhánh làm việc cục bộ: `feature/APDDMP-18-rbac`.
Nền mã nhóm: `5caf2ea3298ad4068627b953a40434a6c685d7e4`.

## Kết quả

**Maven verify: BUILD SUCCESS. 275/275 test qua trên PostgreSQL 16.15, 0 failure, 0 error, 0 skipped.**
Đã kiểm tra thêm **16/16 bước trên JAR production**, không nạp controller thử nghiệm.

| Bộ kiểm thử | Số test | Lỗi |
|---|---:|---:|
| RbacIntegrationTest | 254 | 0 |
| AuthServiceTest có sẵn của 2.5 | 13 | 0 |
| JwtAccessTokenIssuerTest có sẵn | 6 | 0 |
| HealthControllerTest có sẵn | 1 | 0 |
| RoleNameTest có sẵn | 1 | 0 |
| Tổng | 275 | 0 |

Lượt H2 ban đầu: 273/273 qua. Sau đó bổ sung 2 test (cập nhật không đổi và thay đổi quyền đồng thời), chạy toàn bộ 275 test trên PostgreSQL. Không tuyên bố đã chạy 275 test trên H2.

## Môi trường

- Spring Boot 4.0.5, Java target 21 giữ nguyên theo `pom.xml` của nhóm.
- Máy kiểm thử: Windows; Temurin JDK 25.0.4.1; Maven 3.9.11 portable.
- PostgreSQL 16.15 ở cluster test mới, chỉ nghe loopback trên cổng riêng, mật khẩu ngẫu nhiên. Không kết nối DB dev/production đang có của nhóm.
- H2 2.4.240 cho lượt kiểm thử đầu.
- Maven verify đóng gói được `backend/target/smartdrone-backend-0.1.0.jar`.
- Không thay đổi phiên bản Java/framework hoặc cài Maven vào PATH toàn máy.

## Những hành vi đã kiểm tra

1. 39 hàng ma trận độc lập × 5 actor = **195** trường hợp. Đúng vai trò đi tới controller; sai vai trò trả 403 JSON.
2. **39** đường dẫn ma trận không có JWT đều trả 401. PATCH quyền được kiểm tra riêng với cả 5 actor và người chưa đăng nhập.
3. Các role bị chặn khi gọi endpoint chưa khai báo, sai HTTP method, `/integration/**` và method lạ của `/me/permissions`.
4. Đăng ký gửi thêm `role=SYSTEM_ADMIN` vẫn tạo CUSTOMER; khách hàng không tự nâng quyền.
5. Admin cấp được từng vai trò; token trước khi đổi quyền và refresh token cũ bị thu hồi; login lại có quyền mới.
6. Khóa tài khoản chặn access token đang còn hạn và login. Mở lại/cấp lại role cũ không hồi sinh JWT cũ.
7. Payload trống, role không hợp lệ, user không tồn tại, phân trang vượt giới hạn đều có lỗi đúng.
8. Admin tự khóa/tự hạ quyền bị chặn 409. Cập nhật giống dữ liệu cũ không hủy phiên.
9. Hai Admin đồng thời hạ quyền nhau: chỉ một lệnh thành công; lệnh còn lại bị chặn sau kiểm tra lại quyền.
10. JWT sai định dạng, role ghép/list/không tồn tại, subject không phải UUID, tài khoản đã xóa đều bị từ chối.
11. Token 2.5 thiếu `accessVersion` tương thích ở version 0; hết hiệu lực sau khi thay đổi access.
12. Service không thể bị gọi trực tiếp bởi CUSTOMER để bỏ qua bảo vệ URL; `@PreAuthorize` có hiệu lực.
13. CORS preflight không cần token; health và Swagger vẫn truy cập được. Logout cũ vẫn hoạt động.
14. DTO không lộ passwordHash/refreshToken/accessVersion; dữ liệu tài khoản có `Cache-Control: no-store`.

Test dùng server thật trên cổng ngẫu nhiên và JWT ký bằng encoder của ứng dụng. Các route nghiệp vụ tương lai dùng controller thử **chỉ trong src/test**, nên kết quả của chúng chứng minh lớp phân quyền, không chứng minh logic giao hàng.

## Kiểm tra JAR production: 16 bước

- Khởi động JAR với PostgreSQL và health 200.
- DB mẫu đã có bảng users nhưng chưa có access_version được nâng cấp, user cũ giữ nguyên và nhận version 0.
- Đăng ký ứng viên Admin vẫn là CUSTOMER, login thành công.
- Script bootstrap chỉ cấp đúng Admin đầu tiên, thu hồi refresh session; JWT trước bootstrap bị chặn.
- Login lại có SYSTEM_ADMIN; đăng ký user thứ hai vẫn CUSTOMER.
- Bootstrap không cấp thêm Admin khi đã có Admin hoạt động.
- Danh mục role đúng 5; phân trang user hoạt động; CUSTOMER bị 403 khi đọc users.
- CUSTOMER gọi `/v1/orders` trả **404**, xác nhận JAR không chứa nghiệp vụ giả từ test fixture.
- Admin cấp DISPATCHER; token cũ bị 401; login lại đọc quyền mới thành công.

Kết quả máy đọc và tóm tắt Surefire nằm trong `docs/07-testing/APDDMP-18-evidence/`. Không lưu mật khẩu hoặc token trong bằng chứng bàn giao.

## Cách chạy lại

Với JDK 21+ và Maven đã có trên PATH, tại thư mục gốc:

```powershell
mvn -B -f backend/pom.xml verify
```

Mặc định bộ RBAC dùng H2 riêng trong RAM. Muốn dùng PostgreSQL, **chỉ trỏ vào database kiểm thử có thể xóa dữ liệu**: cấu hình test dùng `create-drop`.

```powershell
$env:RBAC_TEST_DATABASE_URL = 'jdbc:postgresql://127.0.0.1:55432/rbac_test'
$env:RBAC_TEST_DATABASE_DRIVER = 'org.postgresql.Driver'
$env:RBAC_TEST_DATABASE_USER = 'postgres'
# Đặt RBAC_TEST_DATABASE_PASSWORD trong môi trường cục bộ; không commit mật khẩu.
mvn -B -f backend/pom.xml verify
```

Trên máy đang bàn giao, Maven portable nằm tại `backend/target/apddmp18-tools/apache-maven-3.9.11/bin/mvn.cmd`.
Lượt chạy của tác vụ dùng cache riêng và gắn Mockito agent lúc khởi động để tránh cơ chế tự attach bị kẹt trong sandbox Windows:

```powershell
$env:JAVA_HOME = 'D:\VSCODE\JAVA'
$toolsDir = Join-Path (Get-Location) 'backend\target\apddmp18-tools'
& "$toolsDir\apache-maven-3.9.11\bin\mvn.cmd" -B -f backend/pom.xml `
  "-Dmaven.repo.local=$toolsDir\m2" `
  "-DargLine=-javaagent:$toolsDir\m2\org\mockito\mockito-core\5.20.0\mockito-core-5.20.0.jar" verify
```

Nếu chạy lại tại đường dẫn có khoảng trắng, cần đặt dấu ngoặc kép cho đường dẫn `-javaagent` hoặc chạy Maven bình thường ngoài sandbox. Không cần thêm tham số này vào production.

## Sai sót nền mã và giới hạn kiểm chứng

- Baseline trước sửa không compile: `ApiException` khai báo hai lần. Đã sửa tối thiểu và bỏ advice trùng tên để chạy được ứng dụng; không viết lại mục 2.1.
- Hai lượt thử PostgreSQL qua shell đặc quyền dừng do lỗi socket Unix-domain của JDK/Windows trước khi chạy test. Chạy Maven trong shell thường, kết nối cùng loại DB test riêng đã thành công; không che giấu các lượt môi trường thất bại bằng cách bỏ test.
- Chưa chạy GitHub Actions, chưa kiểm thử tải/đạt mục tiêu 3 giây, chưa chứng minh scope đơn hàng/trạm vì module đó chưa có.
- Không thay đổi hoặc kiểm thử UI 2.8 và mobile 6.x. Quản lý hồ sơ 2.7 vẫn là phần riêng.
