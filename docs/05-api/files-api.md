# API tệp (ảnh gói hàng, ảnh xác nhận) — Task 3.3 (APDDMP-24)

Phạm vi: cấp quyền tải lên, xác nhận tải lên, cấp link xem có hạn. Lưu trữ trên MinIO (bucket private).
Căn cứ: APDDMP-9 (hợp đồng API), APDDMP-8 (ERD), APDDMP-18 (RBAC). Base: `/api/v1`.

## 1. Luồng tải ảnh

```
Client                    Backend                      MinIO
  | POST /files/upload-tickets |                          |
  |--------------------------->| tạo metadata (PENDING)   |
  |<---------------------------| url + fields (có hạn)    |
  | POST multipart (fields..., file) -------------------->| MinIO kiểm tra key, Content-Type, dung lượng
  |<------------------------------------------------------| 204
  | POST /files/{id}/complete  |                          |
  |--------------------------->| stat + đọc 12 byte đầu ->|
  |<---------------------------| READY (hoặc REJECTED)    |
  | GET /files/{id}/download-link                         |
  |--------------------------->| kiểm tra quyền           |
  |<---------------------------| url ký có hạn            |
  | GET url -------------------------------------------->| ảnh
```

Không dùng được link tải sau khi hết hạn: client xin link mới theo `fileId`. **Không lưu signed URL làm định danh lâu dài; chỉ lưu `fileId`.**

## 2. Endpoint

| Method | Đường dẫn                      | Vai trò                                                          | Thành công                     |
| ------ | ------------------------------ | ---------------------------------------------------------------- | ------------------------------ |
| POST   | `/v1/files/upload-tickets`     | CUSTOMER, STATION_OPERATOR                                       | `201` + `UploadTicketResponse` |
| POST   | `/v1/files/{id}/complete`      | CUSTOMER, STATION_OPERATOR (chỉ chủ tệp)                         | `200` + `FileView`             |
| GET    | `/v1/files/{id}/download-link` | CUSTOMER, STATION_OPERATOR, DISPATCHER (theo phạm vi tài nguyên) | `200` + `DownloadLinkResponse` |

`POST /v1/files/{id}/complete` là **endpoint mới** của task này (cơ chế xác nhận upload); đã bổ sung vào `RbacPolicy` và ma trận test. Response các endpoint đều có `Cache-Control: no-store`.

## 3. Quy tắc nghiệp vụ

- `purpose` và vai trò: `PACKAGE_PHOTO` chỉ CUSTOMER; `CONFIRMATION_PHOTO` chỉ STATION_OPERATOR. Sai cặp → `403 FORBIDDEN`.
- Object key do server sinh dạng `{purpose}/{yyyy}/{MM}/{uuid}.{ext}`; client không chọn key và không bao giờ nhận key.
- Loại cho phép: `image/jpeg`, `image/png`, `image/webp`. Server **không chỉ tin Content-Type**: sau khi upload, `complete` đọc 12 byte đầu để xác định loại thật và so với loại đã khai báo.
- Giới hạn mặc định (cấu hình được, chờ nhóm chốt): 5 MiB, ticket 5 phút, link tải 5 phút. MinIO tự từ chối upload sai kích cỡ/loại; `complete` kiểm tra lại.
- Tối đa 20 ticket đang chờ cho mỗi người dùng (`UPLOAD_LIMIT_REACHED` / 429).
- Quyền xem tệp được kiểm tra **mỗi lần** cấp link: chủ tệp luôn xem được; tệp đã gắn vào tài nguyên thì theo `FileResourceAccessPolicy` của loại tài nguyên đó (ví dụ ORDER). Chưa có policy → chỉ chủ tệp.
- Không có quyền xem hoặc `fileId` không tồn tại đều trả `404 RESOURCE_NOT_FOUND` (không lộ việc fileId có tồn tại).
- Tệp quá cỡ/sai loại/không tồn tại không thể ở trạng thái `READY`, nên không gắn được vào đơn.

## 4. Trạng thái tệp

`PENDING_UPLOAD → READY → ATTACHED`; nhánh lỗi: `REJECTED` (sai loại/quá cỡ, object bị xóa), `EXPIRED` (ticket hết hạn không upload), `DELETED` (READY quá hạn chưa gắn, bị dọn).

## 5. DTO

### UploadTicketRequest

| Trường      | Kiểu   | Bắt buộc | Ràng buộc                               |
| ----------- | ------ | -------- | --------------------------------------- |
| purpose     | enum   | có       | `PACKAGE_PHOTO` \| `CONFIRMATION_PHOTO` |
| contentType | string | có       | `image/jpeg`, `image/png`, `image/webp` |
| sizeBytes   | number | có       | > 0 và ≤ giới hạn cấu hình              |

### UploadTicketResponse

| Trường               | Ý nghĩa                                                            |
| -------------------- | ------------------------------------------------------------------ |
| fileId               | UUID, dùng ở các API sau và để gắn vào đơn                         |
| upload.url           | URL bucket để POST (host công khai)                                |
| upload.method        | luôn `POST`                                                        |
| upload.fileFieldName | luôn `file`                                                        |
| upload.fields        | các field form phải gửi nguyên văn, **field `file` đặt cuối cùng** |
| expiresAt            | hạn dùng ticket (ISO-8601 UTC)                                     |
| maxSizeBytes         | giới hạn dung lượng                                                |

### FileView

`id, purpose, status, contentType, sizeBytes, createdAt`. Không có object key.

### DownloadLinkResponse

`fileId, url, expiresAt`. `url` là link tạm thời (mặc định 5 phút).

## 6. Mã lỗi

| HTTP | code                          | Khi nào                                                                   |
| ---- | ----------------------------- | ------------------------------------------------------------------------- |
| 400  | VALIDATION_ERROR              | thiếu/sai trường request                                                  |
| 401  | AUTH_REQUIRED / TOKEN_EXPIRED | chưa đăng nhập / token hết hạn                                            |
| 403  | FORBIDDEN                     | sai vai trò hoặc sai `purpose`                                            |
| 404  | RESOURCE_NOT_FOUND            | không có tệp hoặc không có quyền xem                                      |
| 409  | FILE_NOT_UPLOADED             | `complete` khi chưa thấy object trên MinIO (có thể thử lại)               |
| 409  | FILE_INVALID_STATE            | tệp không ở trạng thái phù hợp (chưa READY, đã gắn nơi khác, đã bị loại…) |
| 413  | FILE_TOO_LARGE                | vượt giới hạn dung lượng                                                  |
| 415  | FILE_TYPE_NOT_ALLOWED         | sai loại / nội dung không phải ảnh hợp lệ                                 |
| 429  | UPLOAD_LIMIT_REACHED          | quá nhiều ticket đang chờ                                                 |
| 503  | STORAGE_UNAVAILABLE           | MinIO chưa cấu hình hoặc không kết nối được                               |

Các mã mới cần đối chiếu mục 7 của APDDMP-9.

## 7. Gắn tệp vào đơn (cho 3.1, 3.2, 3.4)

Module đơn hàng gọi trực tiếp `FileService.attach(actorId, fileId, expectedPurpose, resourceType, resourceId)`
(không có endpoint HTTP riêng). Hàm này kiểm tra: tệp thuộc người gọi, đúng mục đích, trạng thái `READY`, object còn tồn tại; gắn lặp lại cùng tài nguyên thì an toàn, gắn sang tài nguyên khác bị từ chối.

Để DISPATCHER và operator của trạm được giao xem được ảnh, module đơn hàng đăng ký một bean:

```java
@Component
class OrderFileAccessPolicy implements FileResourceAccessPolicy {
    public String resourceType() { return "ORDER"; }
    public boolean canView(UUID actorId, RoleName role, UUID orderId) { /* chủ đơn / trạm được giao / điều phối */ }
}
```

## 8. Hướng dẫn cho web (Trọng, Luân)

- Dùng `uploadImage(file, purpose)` trong `web/src/lib/fileService.ts` và component `ImageUploader`; nhận `fileId` qua `onUploaded` rồi gửi `fileId` cùng dữ liệu đơn/xác nhận.
- Hiển thị ảnh bằng `SecureImage fileId=...`. Component tự xin link khi mount và **tự xin lại một lần** khi `<img>` lỗi (link hết hạn); nếu vẫn lỗi thì hiện nút thử lại.
- Không lưu `url` vào DB, localStorage hay state lâu dài.
- Upload gửi thẳng lên MinIO bằng axios gốc (không kèm JWT). Origin của web phải nằm trong CORS của MinIO (xem tài liệu triển khai).
