# Dữ liệu và biểu mẫu gói hàng — Task 3.4

Căn cứ: APDDMP-9 (hợp đồng API), APDDMP-8 (ERD), APDDMP-18 (RBAC). Gói là **thành phần của đơn** trong v1:
**không có endpoint `/packages`**, không thêm quy tắc RBAC mới. Task này cung cấp DTO, entity, service dùng chung và
biểu mẫu web để 3.6 (tạo/sửa đơn) và 3.7 (danh sách/chi tiết) nhúng vào.

> ⚠️ Tên và kiểu trường dưới đây được dựng từ mô tả nghiệp vụ (trọng lượng, kích thước, mô tả, ảnh). Phải đối chiếu với
> APDDMP-9 / APDDMP-8 trước khi merge; nếu hợp đồng khác, chỉ cần sửa `PackageInput`, `PackageView`, `OrderPackage`.

## 1. DTO

`PackageInput` — nằm trong trường `package` của yêu cầu tạo/sửa đơn (3.6 khai báo `@Valid PackageInput package`).

| Trường       | Kiểu    | Bắt buộc | Ràng buộc                                              |
| ------------ | ------- | -------- | ------------------------------------------------------ |
| description  | string  | có       | không rỗng, ≤ 500 ký tự (tự cắt khoảng trắng đầu/cuối) |
| weightGrams  | integer | có       | ≥ 1; ≤ `max-weight-grams`                              |
| lengthCm     | number  | có       | > 0; ≤ `max-length-cm`                                 |
| widthCm      | number  | có       | > 0; ≤ `max-width-cm`                                  |
| heightCm     | number  | có       | > 0; ≤ `max-height-cm`                                 |
| photoFileIds | uuid[]  | không    | `min-photos` ≤ số ảnh ≤ `max-photos`; không trùng      |

Thuộc tính thừa (ví dụ `orderId`, `ownerId`) bị từ chối. `PackageView` có đúng các trường trên, ảnh chỉ là `fileId`
(không có URL/object key; web xin link xem qua `GET /v1/files/{id}/download-link`).

## 2. Giới hạn cần nhóm chốt (không hardcode)

Khai báo trong `application.yml` → `app.order-package.*`. **Không có giá trị mặc định trong code**: thiếu/≤ 0 thì app
không khởi động. Giá trị trong file hiện tại chỉ là đề xuất.

| Khóa                       | Đề xuất | Ghi chú                    |
| -------------------------- | ------- | -------------------------- |
| max-weight-grams           | 5000    | chờ nhóm chốt              |
| max-length/width/height-cm | 50      | chờ nhóm chốt              |
| min-photos                 | 0       | có bắt buộc ảnh hay không? |
| max-photos                 | 3       | chờ nhóm chốt              |

Dung lượng ảnh và thời hạn link thuộc cấu hình `app.storage.*` của 3.3.

## 3. Cách 3.6 / 3.7 dùng `PackageService`

```java
// 3.6 — trong cùng transaction tạo đơn; ĐÃ kiểm tra order thuộc customerId và còn được sửa (theo 3.5)
PackageView view = packageService.create(customerId, order.getId(), request.packageInfo());
packageService.replace(customerId, orderId, request.packageInfo());          // PUT /orders/{id}

// 3.7 — người gọi đã kiểm tra quyền xem đơn
Optional<PackageView> one = packageService.findByOrderId(orderId);
Map<UUID, PackageView> page = packageService.findByOrderIds(orderIds);       // 1 truy vấn cho cả trang
```

Quy tắc:

- `create`/`replace` chỉ cho `CUSTOMER` (`@PreAuthorize`), **không tự kiểm tra chủ đơn** vì chỉ biết `orderId` → 3.6 bắt buộc kiểm tra trước.
- Ảnh gắn qua `FileService.attach(customerId, fileId, PACKAGE_PHOTO, "ORDER", orderId)`: chỉ ảnh của chính khách, đã `READY`. Ảnh sai chủ/không tồn tại → `404 RESOURCE_NOT_FOUND`. Lỗi bất kỳ → rollback cùng transaction đơn.
- Mỗi đơn đúng 1 gói (unique `order_id`); tạo lần hai → `409`. 3.6 thêm khóa ngoại `order_id → orders(id)` khi có bảng `orders`.
- Việc đơn ở trạng thái nào thì được sửa gói thuộc 3.5/3.6, không nằm trong service này.
- Danh sách ảnh có thứ tự do khách sắp xếp (`order_package_photos.position`).

## 4. Mã lỗi

| HTTP | code               | Khi nào                                                                |
| ---- | ------------------ | ---------------------------------------------------------------------- |
| 400  | VALIDATION_ERROR   | thiếu/sai trường; vượt giới hạn cấu hình; thiếu/thừa/trùng ảnh         |
| 404  | RESOURCE_NOT_FOUND | ảnh không tồn tại hoặc không phải của khách; `replace` khi chưa có gói |
| 409  | (CONFLICT)         | đơn đã có gói                                                          |
| 409  | FILE_INVALID_STATE | ảnh chưa `READY` hoặc đã gắn vào tài nguyên khác (từ 3.3)              |

Lỗi `VALIDATION_ERROR` do bean validation có `errors[{field,message}]`; lỗi do giới hạn cấu hình chỉ có `message`.

## 5. Dữ liệu

`order_packages(id, order_id UNIQUE, description, weight_grams, length_cm, width_cm, height_cm, created_at, updated_at)`
và `order_package_photos(package_id, position, file_id)`. Hibernate `ddl-auto=update` tự tạo khi chạy local;
script tham khảo: `backend/scripts/3.4-order-packages.sql`.

## 6. Web

- `web/src/lib/packageService.ts`: kiểu, `validatePackageForm`, `toPackageInput`, `fromPackageView`, `mapServerErrors`.
- `features/packages/PackageForm.tsx`: form nhúng được (trọng lượng nhập kg → gửi gram; ảnh qua `ImageUploader`, `purpose=PACKAGE_PHOTO`).
- `features/packages/PackageSummary.tsx`: hiển thị chỉ-đọc (ảnh qua `SecureImage`).
- Demo: `npm run dev` → đăng nhập tài khoản Khách hàng → `/dev/package-form` (chỉ có ở chế độ dev).

## 7. Việc còn lại / phụ thuộc

- `FileService` chưa có thao tác gỡ ảnh: ảnh bị bỏ khỏi danh sách khi `replace` vẫn ở trạng thái `ATTACHED`. Nên đề nghị người làm 3.3 thêm `detach`.
- `OrderFileAccessPolicy` (3.6) cho phép điều phối/trạm xem ảnh gói theo đơn.

## 8. Cụ thể các file

| File                        | Chức năng                                                                                                                                                       |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PackageProperties.java      | Giới hạn các số liệu của gói hàng (như cân nặng tối đa, chiều cao tối đa, tối đa bao nhiêu ảnh)                                                                 |
| PackageService.java         | Chứa nghiệp vụ gói hàng, hàm này sẽ được gọi trong cùng transaction với việc lưu đơn                                                                            |
| PackageInput.java           | DTO đầu vào, là hình dạng dữ liệu gói hàng mà client gửi lên                                                                                                    |
| PackageView.java            | DTO đầu ra, là dữ liệu gói hàng mà server trả về cho client                                                                                                     |
| OrderPackage.java           | Là entity JPA, lớp ánh xạ gói hàng xuống database, thể hiện hình dạng dữ liệu lúc lưu trữ                                                                       |
| OrderPackageRepository.java | Là một repo, lớp truy cập bảng order_packages. Nó là một interface kế thừa JpaRepository<OrderPackage, UUID>, nên Spring Data JPA tự sinh phần cài đặt lúc chạy |

| PackageConfig.java | Báo cho Spring để nạp PackageProperties từ cấu hình |

| 3.4-order-packages.sql | SQL tham khảo để tạo hai bảng trong task 3.4 |
| PackageInputValidationTest.java | Kiểm tra các annotation validation và constructor chuẩn hóa dữ liệu có hoạt động đúng không |
| PackageServiceTest.java | Test đơn vị cho PackageService, dùng Mockito để thay OrderPackageRepository và FileService bằng đối tượng giả |
| PackagePersistenceIntegrationTest.java | Test này khởi động cả ứng dụng Spring với database H2 thật trong bộ nhớ, rồi gọi PackageService thật. Nó kiểm tra những thứ mà mock không kiểm tra được: ánh xạ entity xuống bảng, ràng buộc unique, phân quyền @PreAuthorize, và việc nạp cấu hình. |
| packageService.ts | File này chứa kiểu dữ liệu và các hàm thuần, cho gói hàng ở phía web |
| PackageForm.tsx | Biểu mẫu nhập gói hàng. Nó chỉ là component hiển thị: không tự gọi API đơn hàng và không tự giữ dữ liệu. Dữ liệu nằm ở nơi dùng nó, theo mô hình “controlled component”. |
| PackageSummary.tsx | Đây là thẻ hiển thị chỉ-đọc thông tin gói hàng |
| PackageFormPreviewPage.tsx | Đây là trang demo để bạn thử biểu mẫu khi màn tạo đơn của 3.6 chưa có. Nó không phải một chức năng của sản phẩm. |
| AppRouter.tsx | Là bảng định tuyến của toàn bộ web |

## 9. Cách chạy

### Yêu cầu môi trường

- JDK 21, Maven, Node.js (cho web)
- SQL Server (`localhost:1433`, database `smartdrone`): chỉ cần khi chạy ứng dụng thật, **không cần** khi chạy test
- MinIO + biến môi trường `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`: chỉ cần khi thử tải ảnh

### Cách test backend (không cần SQL Server/MinIO)

Test dùng H2 trong bộ nhớ và mock `FileService`.

```bash
cd backend
mvn test "-Dtest=Package*Test"
```

Hoặc trong VS Code: mở **Testing** → chạy các class trong `com.smartdrone.application.packages` và `com.smartdrone.api.packages`.

| Test                                | Nội dung                                                                              |
| ----------------------------------- | ------------------------------------------------------------------------------------- |
| `PackageInputValidationTest`        | Validation DTO: trim mô tả, số > 0, thiếu/thừa trường                                 |
| `PackageServiceTest`                | Giới hạn cấu hình, ảnh trùng/thừa, tạo lần hai → 409, `replace` khi chưa có gói → 404 |
| `PackagePersistenceIntegrationTest` | Lưu/đọc trên H2, giữ thứ tự ảnh, unique theo đơn, chỉ `CUSTOMER` được ghi             |

Kết quả khi chạy: 3/3 test class đạt.

### Cách chạy và thử biểu mẫu trên web

1. Bật SQL Server theo `docker-compose.yml`, rồi chạy backend (class `SmartDroneApplication`, cổng 5000). Bảng `order_packages` và `order_package_photos` được Hibernate tự tạo (`ddl-auto=update`).
2. Chạy web:
   ```bash
   cd web
   npm install
   npm run dev
   ```
3. Đăng nhập bằng tài khoản **Khách hàng**, mở `http://localhost:5173/dev/package-form`.
4. Các ca nên thử:

| Nhập                                  | Kết quả mong đợi                                                       |
| ------------------------------------- | ---------------------------------------------------------------------- |
| Để trống, bấm "Kiểm tra dữ liệu"      | Báo lỗi từng ô                                                         |
| Trọng lượng > 5 kg                    | "Trọng lượng tối đa 5 kg"                                              |
| Kích thước > 50 cm                    | "... tối đa 50 cm"                                                     |
| Dữ liệu hợp lệ (ví dụ 2 kg, 24×12×12) | Hiện tóm tắt và payload `{ "package": {... "weightGrams": 2000 ...} }` |

Ô mô tả có `maxLength=500` nên trình duyệt tự chặn khi gõ quá 500 ký tự (giới hạn này được kiểm tra ở test backend).
Trang `/dev/package-form` không gọi API đơn hàng (chưa có task 3.6) và không có mục trên menu; truy cập bằng đường dẫn trực tiếp.

### Lưu ý

- **Tải ảnh** dùng `ImageUploader` của task 3.3, cần backend + MinIO chạy, logic ảnh (trùng, quá số lượng, sai chủ, thứ tự) được kiểm tra bằng test với `FileService` mock.
- Giới hạn trong `application.yml` (`app.order-package.*`: 5000 g, 50 cm, 0–3 ảnh) là **đề xuất, chờ nhóm chốt**. Thiếu hoặc ≤ 0 thì ứng dụng không khởi động (chủ ý).
- Tên và kiểu trường được dựng từ mô tả nghiệp vụ; cần đối chiếu APDDMP-9 / APDDMP-8 trước khi merge. Nếu khác, chỉ cần sửa `PackageInput`, `PackageView`, `OrderPackage`.
- `FileService` chưa có thao tác gỡ ảnh: ảnh bị bỏ khỏi danh sách khi `replace` vẫn ở trạng thái `ATTACHED`. Đề nghị người làm 3.3 thêm `detach`.
- Khi merge, kiểm tra các file dùng chung (`application.yml`, `customer.css`, `AppRouter.tsx`) không ghi đè phần của task khác.
