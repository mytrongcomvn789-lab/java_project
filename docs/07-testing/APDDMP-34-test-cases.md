# APDDMP-34 — Bảng test case 4.5: Xác nhận nhận gói tại trạm

Kiểm tra ngày 17/11/2026. Kết quả dưới đây là thực chạy trên Windows/JDK 25, H2 MSSQLServer mode và Chrome; không thay thế nghiệm thu SQL Server/realtime/lịch giao thật.

## Backend: 21 lượt test đạt

Nguồn: `backend/src/test/java/com/smartdrone/api/orders/OrderReceiptIntegrationTest.java`.

| Mã | Ca kiểm tra | Kết quả mong đợi đã kiểm chứng | Số lượt | Kết quả |
| --- | --- | --- | ---: | --- |
| BE01 | Nhân viên đúng trạm nhận đúng kiện/lần giao | 200, PREPARING, ETag tăng; đúng actor/time/note/attempt; lịch sử lưu, slot vẫn ACTIVE | 1 | Đạt |
| BE02 | Hàng đợi/context chỉ thuộc trạm gửi | Đúng trạm thấy đơn; trạm đích không thấy hàng đợi, context/POST trả 404; chưa phân công cũng 404 | 1 | Đạt |
| BE03 | CUSTOMER, DISPATCHER, LOGISTICS_MANAGER, SYSTEM_ADMIN gọi API nhận | Cả POST và hai GET trả 403, kể cả khi có phân công trạm | 4 | Đạt |
| BE04 | Không JWT | POST trả 401, không đổi đơn | 1 | Đạt |
| BE05 | Thu hồi phân công sau khi mở đơn | POST trả 404 với token cũ, không đổi đơn | 1 | Đạt |
| BE06 | Body thiếu ID, UUID sai, ghi chú 501 ký tự, thêm actorId | 400; không ghi thêm lịch sử | 1 | Đạt |
| BE07 | Mã kiện hoặc attempt không khớp | Sai kiện 400, sai attempt 409; không đổi đơn | 1 | Đạt |
| BE08 | Attempt đã RELEASED | 409, không nhận kiện | 1 | Đạt |
| BE09 | Attempt có hai trạm không khớp đơn | 409, không nhận kiện | 1 | Đạt |
| BE10 | Attempt ACTIVE không khớp lịch sử SCHEDULE gần nhất | 409, không nhận kiện | 1 | Đạt |
| BE11 | Dữ liệu có nhiều attempt ACTIVE | 409 thay vì 500; không tự chọn bừa một lần giao | 1 | Đạt |
| BE12 | If-Match thiếu, sai định dạng, cũ | Lần lượt 428, 400, 412; không đổi đơn | 1 | Đạt |
| BE13 | Đơn PENDING chưa lên lịch | 409; giữ nguyên PENDING | 1 | Đạt |
| BE14 | Gửi lại sau nhận thành công | Version cũ 412, version mới nhưng PREPARING trả 409; lịch sử chỉ một RECEIVE; rời hàng đợi | 1 | Đạt |
| BE15 | Hai nhân viên nhận đồng thời | Một 200, một 412; chỉ một RECEIVE và version tăng một | 3 | Đạt |
| BE16 | Transaction rollback | Trạng thái và lịch sử đều hoàn nguyên | 1 | Đạt |

BE03 chạy theo 4 role; BE15 lặp 3 lần. Tổng 21 lượt, không bỏ qua.

## Trình duyệt: 8/8 đạt

| Mã | Thao tác thực hiện | Kết quả |
| --- | --- | --- |
| UI01 | Đăng nhập nhân viên trạm gửi, bấm menu Tiếp nhận kiện hàng | Thấy đúng 3 đơn SCHEDULED trong dữ liệu thử |
| UI02 | Mở đơn, xem người gửi/kiện/lần giao; thử trước và sau checkbox; quay lại ở bước xác nhận | Không gửi khi chưa kiểm tra; quay lại chưa làm đổi đơn |
| UI03 | Gọi API từ phiên đăng nhập với kiện sai/attempt sai/version cũ | 400/409/412, không gây tác dụng phụ |
| UI04 | Nhập ghi chú và nhận kiện qua giao diện, tải lại | PREPARING, ghi chú và lịch sử được lưu; nút nhận biến mất |
| UI05 | Hai tab cùng mở một đơn rồi lần lượt xác nhận | Tab sau gặp xung đột; tải lại chỉ một lần nhận |
| UI06 | Đăng nhập operator chỉ thuộc trạm đích | Không thấy hàng đợi trạm gửi; mở trực tiếp context bị chặn |
| UI07 | Đăng nhập khách hàng | Xem được RECEIVE ở lịch sử đơn, không được truy cập màn hình nhận kiện |
| UI08 | Trở về hàng đợi, kiểm tra lỗi JavaScript | Đơn đã nhận rời danh sách; còn một đơn chưa nhận; không có unhandled exception |

Kịch bản thực chạy: `C:\Users\Cong Nha\Documents\Codex\APDDMP-34-qa\browser-receipt.mjs`.
[Bằng chứng kết quả](APDDMP-34-evidence/browser-results.json) · [Ảnh nhận thành công](APDDMP-34-evidence/03-received.png) · [Ảnh tab xung đột](APDDMP-34-evidence/04-stale-tab.png).

## Kiểm tra toàn hệ thống

- `mvn -B verify`: **419 tổng, 410 đạt, 9 bỏ qua, 0 failure, 0 error**.
- `npm run build`: TypeScript và Vite thành công.
- 9 test cũ chưa chạy: 7 MinIO thiếu cấu hình; 2 OrderIntegrationTest cần ORDER_TEST_DATABASE_URL.
- Ma trận RBAC bổ sung GET hàng đợi/context; POST receipts chuyển từ kỳ vọng endpoint giả 204 sang 400 khi body trống ở controller thật. Những role bị cấm vẫn phải 403.

## Hướng dẫn nhóm kiểm tra lại

1. Checkout nhánh `4.5-station-package-receipt`; cài dependency web nếu chưa có.
2. Trong backend chạy `mvn -B -Dtest=OrderReceiptIntegrationTest test`, sau đó `mvn -B verify`.
3. Trong web chạy `npm ci` và `npm run build`.
4. Chuẩn bị operator được gán trạm gửi, đơn SCHEDULED có một attempt ACTIVE khớp dòng SCHEDULE, và kiện thuộc đơn. Không dùng dữ liệu khách hàng thật.
5. Đăng nhập operator, mở `/station/parcels`, làm UI01–UI08; đối chiếu API, lịch sử và version.
6. Sau khi 4.2 và 4.3 được tích hợp, chạy checklist còn chờ dưới đây rồi mới nghiệm thu toàn luồng.

## Chưa kiểm chứng — để mở khi review

- [ ] API 4.2 tạo lịch thật → 4.5 nhận kiện, không dùng fixture lịch.
- [ ] Trạng thái nhận kiện phát realtime trên nhánh 4.3 sau merge.
- [ ] Chạy bộ liên quan trên SQL Server thật.
- [ ] Kiểm thử MinIO/ảnh với dịch vụ thật nếu đưa ảnh vào đợt nghiệm thu.
- [ ] Người review độc lập chạy lại và xác nhận kết quả.

Lịch giao trong demo H2 là fixture có ghi chú rõ. Chỉ API và UI nhận kiện 4.5 chạy thật; không ghi nhận hoàn thành thay 4.2/4.3/4.4.

[Tài liệu API và triển khai](../05-api/station-receipt-api.md) · [Báo cáo tổng hợp](APDDMP-34-report.md).
