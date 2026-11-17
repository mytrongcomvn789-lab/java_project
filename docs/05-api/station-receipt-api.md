# 4.5 — Tiếp nhận kiện tại trạm gửi (APDDMP-34)

## Mục tiêu và ranh giới

Nhân viên trạm gửi kiểm tra kiện thực tế, xác nhận RECEIVE và chuyển đơn từ SCHEDULED sang PREPARING. Khác bước khách nhận hàng/OTP ở 4.7. Java Spring Boot và React hiện có; không thêm backend khác.

Phụ thuộc 4.2: phải có một DeliveryAttempt ACTIVE, khớp đơn/hai trạm và attemptId trong dòng lịch sử SCHEDULE gần nhất. Có thể triển khai trước 4.4: đầu ra PREPARING là đầu vào DISPATCH của 4.4. Nhánh 4.3 chưa merge; 4.5 gọi OrderTransitionService chung để sau khi 4.3 tích hợp, sự kiện chuyển trạng thái đi qua cùng nguồn. Không tự viết publisher thứ hai, không tuyên bố đã test realtime 4.3.

## API

Mọi URL dưới đây có tiền tố /api. JWT của STATION_OPERATOR là bắt buộc ở cả filter và service. Nhân viên phải được phân công ở pickupStationId; quyền xem đơn ở trạm đích không đồng nghĩa quyền tiếp nhận.

| Method và URL | Nội dung |
| --- | --- |
| GET /v1/station-receipts | Danh sách SCHEDULED theo trạm gửi được gán. Hỗ trợ code, from, to, stationId, sort, page, pageSize giống danh sách đơn; status cố định ở server |
| GET /v1/orders/{id}/receipt-context | OrderDetail, senderName, packageId và attempt {id, attemptNo, slotStart, slotEnd}; ETag của đơn |
| POST /v1/orders/{id}/receipts | If-Match bắt buộc. Nhận kiện, trả OrderDetail và ETag mới |

Ví dụ body POST:

```json
{
  "attemptId": "UUID của lần giao ACTIVE",
  "packageId": "UUID của kiện thuộc đơn",
  "note": "Đã đối chiếu mã kiện, bao bì nguyên vẹn"
}
```

- attemptId/packageId bắt buộc, ghi chú tùy chọn tối đa 500 ký tự. Trường JSON lạ bị từ chối.
- actorId và thời gian không nhận từ client: actor lấy từ JWT, thời điểm từ server.
- Không có kiện/lần giao hợp lệ thì không cho nhận, kể cả client tự gửi ID.
- Cả ba endpoint trả Cache-Control: no-store. Context/POST trả ETag, CORS hiện có cho phép web đọc ETag.
- 401: chưa xác thực; 403: sai vai trò; 404: đơn không tồn tại hoặc không thuộc trạm gửi được gán.
- 400: dữ liệu sai/thiếu, kiện không khớp, If-Match sai định dạng; 428: thiếu If-Match.
- 412: phiên bản đã thay đổi; 409: trạng thái sai, attempt hết hiệu lực/không khớp/không duy nhất.
- Gửi lại sau thành công với If-Match cũ trả 412; dùng phiên bản mới trên đơn PREPARING trả 409. Đây là chống tác dụng lặp, không phải trả lại 200 kiểu idempotent replay.

## Lưu dữ liệu và tính nhất quán

Dùng lại OrderTransitionService và lịch sử order_status_history, trong cùng transaction. Lưu RECEIVE, actorId, actorRole, reason (ghi chú), occurredAt, attemptId và orderVersion. Order @Version cùng unique (order_id, order_version) bảo đảm cạnh tranh chỉ một lần nhận thành công; bên còn lại 412. Rollback hoàn nguyên cả trạng thái và lịch sử.

Không tạo bảng receipt mới: v1 có một kiện/đơn, dữ liệu kiện không được sửa khi SCHEDULED/PREPARING. packageId được đối chiếu với order_packages; lịch sử gắn orderId và attemptId. Không giải phóng slot khi nhận; DeliveryAttempt vẫn ACTIVE để quy trình giao hàng tiếp tục quản lý.

Không áp thêm quy tắc cấm nhận sớm/muộn ngoài trạng thái và lần giao: Jira chưa chốt cửa sổ tiếp nhận riêng. Không coi thao tác này là hoàn tất giao hàng.

## Giao diện và cách demo

1. Đăng nhập nhân viên trạm gửi; chọn **Tiếp nhận kiện hàng** (/station/parcels).
2. Danh sách chỉ gồm đơn chờ nhận thuộc trạm gửi, có lọc và phân trang.
3. Mở đơn: xem trạm, người gửi, mã kiện, khối lượng/kích thước và lần giao.
4. Đánh dấu đã kiểm tra kiện, nhập ghi chú → **Tiếp tục nhận kiện**.
5. Chọn **Quay lại** để chưa lưu, hoặc **Xác nhận nhận kiện** để gửi API.
6. Thành công: đơn PREPARING, hiển thị lịch sử nhận; reload vẫn còn. Đơn rời danh sách chờ nhận.
7. Hai tab cùng mở: tab nhận sau gặp xung đột và phải tải lại; không ghi lịch sử lần hai.
8. Khi timeout/5xx, UI yêu cầu tải lại để xác minh kết quả trước khi thử tiếp.

Đường dẫn chi tiết: /station/parcels/{orderId}. Menu cũ trước đây là placeholder đã được nối vào màn hình thật. Không sửa màn hình nghiệp vụ của thành viên khác ngoài thêm chế độ danh sách dùng chung.

## File chính

- Backend: OrderReceiptController, OrderReceiptService, ReceivePackageRequest, ReceiptContext, RbacPolicy.
- Web: receiptService.ts, StationParcelsPage.tsx, StationReceiptPage.tsx; nối OrdersPage/AppRouter/navigation.
- Test: OrderReceiptIntegrationTest, rbac-matrix.csv (endpoint thật thay fixture 204; thêm kiểm quyền hai GET).
- Không đổi schema database và không sửa luật chuyển trạng thái chung.

## Kiểm chứng và phần bàn giao còn phụ thuộc

Xem [báo cáo kiểm thử](../07-testing/APDDMP-34-report.md). Nghiệm thu luồng đầy đủ 4.2 → 4.5 → 4.4 cần lịch giao thật từ API 4.2 và tích hợp các nhánh liên quan. Demo local dùng H2 riêng và lịch thử có ghi rõ fixture; không thay dữ liệu SQL Server của nhóm.
