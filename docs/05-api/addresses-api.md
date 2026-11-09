# APDDMP-22 — Hồ sơ khách hàng và sổ địa chỉ (3.1)

Ngày triển khai: 09/11/2026. Nền: main ebd313f; Java 21 / Spring Boot 4.0.5.
Nhánh: feature/APDDMP-22-customer-addresses.

## Phạm vi thực hiện

Dùng lại GET/PATCH /api/v1/me của 2.7, bổ sung giao diện hồ sơ thật.
Thêm CRUD địa chỉ của CUSTOMER, phân trang, kiểm tra chủ sở hữu và giao diện sổ địa chỉ.
Component AddressPicker chọn địa chỉ và trả Address (có id) cho form gọi nó.
Trang /deliveries/new hiển thị bước chọn địa chỉ; chưa triển khai tạo đơn 3.6.

Không tạo /customers hay quyền xem toàn bộ khách hàng cho dispatcher.
Không viết lại đăng nhập, đổi mật khẩu hoặc phân quyền của nhóm.

## API địa chỉ

Cần Bearer JWT của CUSTOMER. Người dùng được lấy từ subject token và kiểm tra tài khoản hiện tại bởi bộ xác thực 2.6.
Bốn vai trò khác trả 403; không token trả 401.
Không có GET /addresses/{id} trong hợp đồng v1; dữ liệu xem/sửa lấy từ danh sách.

| Method | Endpoint | Thành công |
|---|---|---|
| GET | /api/v1/addresses?page=1&pageSize=20 | 200, {items,page,pageSize,total} |
| POST | /api/v1/addresses | 201, Address + Location |
| PUT | /api/v1/addresses/{id} | 200, Address |
| DELETE | /api/v1/addresses/{id} | 204, body rỗng |

Sort: createdAt DESC, id ASC. page >= 1, pageSize 1–100 (mặc định 20).
total chỉ đếm địa chỉ đang hoạt động thuộc người gọi. Tất cả response thành công có Cache-Control: no-store.

### Body POST/PUT

```json
{
  "label": "Nhà riêng",
  "recipientName": "Nguyễn An",
  "phone": "0901234567",
  "addressLine": "10 Nguyễn Huệ, TP.HCM",
  "latitude": 10.7769,
  "longitude": 106.7009
}
```

Sáu trường đều bắt buộc theo AddressInput trong openapi.json đính kèm APDDMP-9.
label/recipientName/addressLine: strip khoảng trắng đầu/cuối, không rỗng, tối đa 500 ký tự.
phone: 9–15 chữ số, có thể bắt đầu bằng +; tọa độ latitude [-90,90], longitude [-180,180].
PUT thay thế đầy đủ sáu trường; đây không phải PATCH.
Thuộc tính ngoài hợp đồng (id, customerId, userId, owner, deleted...) bị từ chối 400.
Address response chỉ gồm id và sáu trường trên, không trả entity/owner.

400: dữ liệu không hợp lệ, query phân trang sai, UUID sai.
404 RESOURCE_NOT_FOUND: địa chỉ không tồn tại, đã xóa hoặc thuộc khách khác.
Cập nhật/xóa dùng truy vấn có ownerId và deleted=false; khóa hàng khi ghi để edit đồng thời không làm sống lại dòng đã xóa.

## ERD và quyết định tương thích

Đã đọc APDDMP-8 (JavaSQL.drawio.png) và AddressInput/Address trong OpenAPI APDDMP-9.
ERD dùng integer/bigint, street/ward/district, recipientPhone, isDefault và servingStationID.
Code Java đang dùng UUID, API quy định phone/addressLine và chưa có default/station trên AddressInput.
Bản này theo UUID đang có và hợp đồng API đã công bố; không đổi User/Role của bạn trong nhóm.
Đây là điều chỉnh vật lý so với ERD: bảng addresses, cột address_line/phone và độ dài 500 theo API.
Chưa tự thêm trường mặc định, phục vụ theo trạm hoặc endpoint ngoài OpenAPI.
Địa chỉ người nhận chưa có nghĩa hệ thống đã hỗ trợ drone giao tận nhà; v1 vẫn theo mô hình trạm–trạm.

Schema local dùng Hibernate ddl-auto=update của dự án; H2 profile tạo/xóa schema khi chạy.
Có script backend/scripts/APDDMP-22-addresses.sql cho môi trường PostgreSQL muốn tạo bảng trước.
Script không tự chạy và không phải hệ thống migration mới.

## Bàn giao cho 3.6 — quan trọng

Frontend: import AddressPicker; cung cấp value: string|null và onChange(Address|null).
Giữ address.id trong trạng thái form, gửi vào trường addressId của hợp đồng tạo đơn sau khi nhóm thống nhất tên DTO.
Không coi dữ liệu chọn trên web là bằng chứng quyền sở hữu.

Backend: gọi AddressService.snapshotForOrder(customerIdFromJwt, addressId) trong transaction tạo đơn.
Hàm trả bản sao giá trị AddressView bất biến, kiểm tra owner + địa chỉ chưa xóa.
3.6 phải LƯU bản sao này vào addressSnapshot của đơn (ví dụ JSONB), không dựng lại địa chỉ lịch sử bằng join vào bảng addresses.
Xóa sổ địa chỉ là xóa mềm, giữ khóa tham chiếu; chỉnh sửa chỉ thay đổi bản ghi sổ.
Test hiện tại xác nhận bản sao không đổi sau sửa/xóa. Repo chưa có Order nên chưa có test lưu và đọc snapshot của đơn thật; phần đó thuộc tích hợp 3.6.
Địa chỉ bị xóa/sai chủ không được dùng để tạo đơn mới.

## Các file chính

| File | Công việc |
|---|---|
| Address.java | Entity địa chỉ, chủ sở hữu, thời điểm, cờ xóa |
| AddressRepository.java | Truy vấn giới hạn chủ sở hữu, phân trang, khóa ghi |
| AddressService.java | CRUD và tạo bản sao cho đơn |
| AddressInput / AddressView / AddressPage | Validation và DTO API |
| AddressController.java | 4 route, JWT, role và HTTP response |
| AddressIntegrationTest.java | API thật qua HTTP: CRUD, quyền, validation, pagination, snapshot |
| AddressesPage.tsx | Danh sách, thêm/sửa/xóa có xác nhận, trạng thái tải/lỗi |
| AddressPicker.tsx | Chọn địa chỉ phân trang, callback cho form đơn |
| ProfilePage.tsx / profileService.ts | Đọc/sửa hồ sơ qua API đã có |
| AuthContext.tsx | Cập nhật tên hiển thị/session sau khi lưu hồ sơ |

Các route address giả trong BusinessEndpointProbeController và rbac-matrix.csv được thay bằng kiểm thử API địa chỉ thật; không bỏ kiểm tra quyền. Chính sách RbacPolicy giữ nguyên.

## Chạy và demo

Yêu cầu Java 21, Maven và Node/npm trên PATH.

Terminal 1 tại backend:
```powershell
mvn spring-boot:run "-Dspring-boot.run.profiles=h2"
```
H2 chỉ dùng demo, dữ liệu mất khi dừng. Nếu dùng PostgreSQL của nhóm, chạy không bật h2 sau khi cấu hình DB.

Terminal 2 tại web:
```powershell
npm ci
npm run dev
```

web/.env: VITE_API_URL=http://localhost:5000/api; tắt VITE_DEMO_AUTH hoặc đặt false.
Mở http://localhost:5173; đăng ký/đăng nhập CUSTOMER thật.
Mở Hồ sơ, sửa tên/số điện thoại; vào Địa chỉ giao hàng để thêm/sửa/xóa; kiểm tra sau tải lại trang.
Vào Tạo yêu cầu giao hàng để thử chọn địa chỉ (chưa có nút gửi đơn thật).
Đăng nhập tài khoản khách khác để xác nhận không thấy địa chỉ người trước.

Kiểm thử:
```powershell
# tại backend
mvn test
# tại web
npm run build
```
Kết quả chạy thực tế và giới hạn ghi trong docs/07-testing/APDDMP-22-verification.md.
