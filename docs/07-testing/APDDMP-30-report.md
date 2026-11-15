# APDDMP-30 — Báo cáo thực hiện 4.1

Ngày: 15/11/2026 (Asia/Saigon). Nhánh: `4.1-order-approval`, phát triển từ main `b26d2fe`.
Jira: [APDDMP-30](https://nnha90190-1791007728869.atlassian.net/browse/APDDMP-30).

## Mục tiêu và đầu ra

Điều phối viên đã được phân công trạm có thể duyệt hoặc từ chối đơn PENDING. Quyết định có người thao tác, thời điểm, lý do và lịch sử; hai quyết định cạnh tranh không thể cùng thành công. Có UI React dùng API thật, Java Spring Boot xử lý nghiệp vụ, tài liệu API và test.

Không triển khai giao diện Flutter, lên lịch 4.2, WebSocket 4.3 hoặc gửi thông báo 4.4 trong thay đổi này. Đầu ra APPROVED thật và sự kiện sau commit đã sẵn sàng cho các phần đó.

## Bảng công việc và file thay đổi

| Phần | Thêm/sửa/xóa | Mục đích và cách triển khai |
|---|---|---|
| HTTP | Thêm OrderReviewController, RejectOrderRequest | POST approve/reject; JWT, If-Match; lý do bắt buộc 1–500 ký tự; chặn trường JSON thừa |
| Nghiệp vụ | Thêm OrderReviewService | Nạp đơn theo phạm vi trạm; gọi OrderTransitionService của 3.5 trong transaction; trả detail/ETag mới |
| Phân công | Thêm StaffStationAssignment và repository | Bảng liên kết nhân viên–trạm, unique và khóa ngoại; không có quyền mặc định |
| Phạm vi | Xóa TemporaryStaffStationScope, thêm DatabaseStaffStationScope | Bỏ bản trả rỗng tạm; truy vấn phân công thật và kiểm tra tài khoản/role đang hiệu lực |
| Sự kiện | Thêm OrderReviewedEvent, OrderReviewEventPublisher | Payload bất biến; chỉ phát sau commit; rollback không phát; lỗi listener không biến REST đã commit thành lỗi |
| Lịch sử | Sửa StatusHistoryView, OrderStatusHistory | Trả actorId; reason dùng Unicode để giữ tiếng Việt trên SQL Server |
| Browser API | Sửa SecurityConfig, orderService.ts | Expose ETag qua CORS; web nhận ETag từ GET và gửi đúng If-Match khi quyết định |
| Màn hình chờ | Thêm DispatchRequestsPage, sửa OrdersPage/router/navigation | Dùng lại list 3.7, mặc định/cố định PENDING; chỉ DISPATCHER vào trang chờ duyệt |
| Quyết định | Thêm OrderReviewActions, sửa OrderDetailPage | Xác nhận trước khi gửi; bắt buộc lý do; khóa nút đang gửi; nhận kết quả thật; lỗi phiên bản yêu cầu tải lại; hiển thị actor/lịch sử |
| Database | Thêm 2 script 4.1 trong backend/scripts | DDL và hướng dẫn cấp trạm có kiểm tra; không tự đổi role hoặc cấp toàn hệ thống |
| Test | Thêm OrderReviewIntegrationTest; sửa rbac-matrix.csv | Thay kỳ vọng endpoint probe giả bằng lỗi validation của API thật; thêm ca nghiệp vụ/phạm vi/race/rollback |
| Tài liệu | order-review-api.md, báo cáo này | Hợp đồng API, mô hình trạng thái/sequence, hướng dẫn demo và giới hạn |

Đường dẫn Java tương đối từ `backend/src/main/java/com/smartdrone`; giao diện từ `web/src/features/orders`.

## Kết quả kiểm thử thực thi

| Hạng mục | Kết quả |
|---|---|
| Toàn bộ Maven clean verify | **385 test được liệt kê; 376 đạt, 9 bỏ qua, 0 failure/error — BUILD SUCCESS** |
| Bộ API 4.1 mới | **20/20 đạt, không bỏ qua** |
| Web production build | **TypeScript + Vite đạt** |
| Chrome gọi API thật | **9/9 kịch bản đạt, 0 lỗi JavaScript ngoài xử lý** |
| Màn hình nhỏ | Form từ chối kiểm tra ở 390px; lịch sử cho cuộn ngang để đọc đầy đủ |

Môi trường: Windows, Java 25.0.4.1 (project target Java 21), Maven trong mvnd 1.0.6, H2 2.4.240 chế độ MSSQLServer, Chrome/Playwright. Lệnh Maven dùng `-DforkCount=0` do vấn đề Surefire fork/đường dẫn khác ổ đĩa đã ghi nhận trên máy trước đó.

**9 test bỏ qua thuộc các phần trước:** 7 MinIO và 2 concurrency của OrderIntegrationTest yêu cầu database ngoài. Không tính chúng là đạt. Ba lần chạy race trong bộ 4.1 mới **đã thực sự chạy** trên H2; không suy diễn thành bằng chứng SQL Server đã được kiểm thử.

## Các ca 4.1 (20 lần chạy)

| Ca | Kiểm tra | Kết quả |
|---|---|---|
| Approve | Status APPROVED, version 1, ETag, updatedAt, audit actor/role/time/action, event đúng ID/version | Đạt |
| Reject | Lý do tiếng Việt được trim, lưu DB, hiện trong history của khách | Đạt |
| Input | Thiếu/rỗng/toàn khoảng trắng/>500 ký tự, body thiếu, actorId thừa | Đạt |
| Role ×4 | CUSTOMER, SYSTEM_ADMIN, STATION_OPERATOR, LOGISTICS_MANAGER không được approve/reject dù có phân công | 4 đạt |
| Anonymous | Cả approve/reject trả 401 | Đạt |
| Scope | Chưa cấp trạm, trạm ngoài phạm vi, đơn không tồn tại: trả 404; danh sách rỗng | Đạt |
| Dropoff scope | Phân công trạm nhận cho phép đọc/quyết định theo cùng quy tắc 3.7 | Đạt |
| Revoke | Xóa phân công, JWT còn hiệu lực cũng không xử lý được | Đạt |
| If-Match | Thiếu 428, sai định dạng 400, cũ 412; không đổi trạng thái/lịch sử | Đạt |
| Processed | Đơn đã APPROVED với version hiện tại vẫn không được duyệt/từ chối lần nữa | Đạt |
| Cancelled | Đơn khách đã hủy không thể được duyệt/từ chối | Đạt |
| Race ×3 | Hai điều phối viên cùng version approve/reject: đúng một 200 và một 412; một lịch sử quyết định và một event | 3 đạt |
| Rollback | Transaction rollback: đơn PENDING/version0, không thêm history, không phát event trước/sau rollback | Đạt |
| Event consumer | Listener ném lỗi sau commit: REST vẫn 200, quyết định đã lưu | Đạt |
| CORS | Origin web được phép đọc ETag thực tế | Đạt |

## 9 kịch bản UI chạy trực tiếp

1. Điều phối viên thấy đúng 3 đơn PENDING thuộc trạm được cấp.
2. Duyệt qua nút xác nhận; hủy xác nhận trước khi gửi vẫn an toàn; tải lại vẫn APPROVED và nút xử lý đã ẩn.
3. Lý do toàn khoảng trắng bị chặn; nhập tiếng Việt rồi từ chối, tải lại vẫn giữ lý do.
4. Khách thấy kết quả/lý do nhưng không có nút quyết định và không vào được trang chờ duyệt.
5. Điều phối viên chưa được cấp trạm thấy rỗng, không mở được detail dù biết UUID.
6. Hai màn hình giữ cùng phiên bản: người thứ nhất duyệt; người thứ hai từ chối nhận cảnh báo xung đột, tải lại thấy kết quả đã duyệt.
7. Đơn đã xử lý biến mất khỏi danh sách chờ.
8. Browser đọc được ETag để gửi If-Match.
9. Không có JavaScript exception ngoài xử lý.

## Cách demo lại cho nhóm/thầy

1. Chạy backend và web theo README; tạo tài khoản CUSTOMER và DISPATCHER hợp lệ.
2. Tạo hai trạm thật; cấp trạm cho DISPATCHER bằng script hướng dẫn trong tài liệu API.
3. Khách tạo 2 đơn PENDING; điều phối viên mở menu Yêu cầu chờ duyệt.
4. Duyệt một đơn; từ chối đơn còn lại với lý do; mở lại bằng tài khoản khách để xem kết quả.
5. Tạo thêm một đơn và mở cùng lúc ở hai điều phối viên cùng phạm vi. Duyệt ở màn hình thứ nhất, thử từ chối ở màn hình thứ hai để trình diễn xung đột.
6. Mở history để chỉ ra actorId, role, thời điểm, hành động, lý do và phiên bản.

Dữ liệu dùng cho buổi kiểm tra là dữ liệu giả riêng trong H2, không sửa SQL Server dùng chung của nhóm. H2 create-drop nên mất dữ liệu khi tắt backend.

## Bằng chứng

- [Kết quả 20 test Java](APDDMP-30-evidence/OrderReviewIntegrationTest.txt)
- [Tổng hợp các suite backend](APDDMP-30-evidence/backend-summary.json)
- [Kết quả browser](APDDMP-30-evidence/browser-results.json)
- [Đơn đã duyệt](APDDMP-30-evidence/approved.png)
- [Giao diện 390px sau kiểm tra bố cục](APDDMP-30-evidence/mobile.png)
- [Thông báo xung đột phiên bản](APDDMP-30-evidence/version-conflict.png)
- [Hợp đồng API và sơ đồ](../05-api/order-review-api.md)

## Giới hạn cần biết khi review

- Chưa chạy bộ này trên SQL Server thực; script SQL Server được cung cấp để nhóm kiểm tra trên database test riêng. Test H2 không chứng minh mọi hành vi khóa/collation SQL Server.
- Event trong tiến trình là best-effort, chưa có outbox/retry bảo đảm giao nhận khi server chết. 4.3/4.4 cần bổ sung nếu yêu cầu reliable delivery.
- Cấp trạm qua script quản trị, chưa có UI quản trị phân công.
- Flutter/MinIO không thuộc phần triển khai 4.1 này và không được ghi là đã kiểm chứng.
- Chờ review PR trước khi merge và chuyển Jira Done.
