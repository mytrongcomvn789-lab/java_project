# [APDDMP-34] 4.5 Xác nhận nhận gói tại trạm — test case và kết quả kiểm thử

## Task 4.5 — Xác nhận nhận gói tại trạm (APDDMP-34)

Jira: https://nnha90190-1791007728869.atlassian.net/browse/APDDMP-34
Nhánh bàn giao: `4.5-station-package-receipt`.
Issue dùng để nhóm review/test lại; không phải báo tất cả giai đoạn 4 đã hoàn thành.

### Chức năng
Nhân viên **trạm gửi** xác nhận đúng kiện và lần giao ACTIVE: **SCHEDULED → PREPARING**, qua state machine chung. Ghi actor, thời gian server, ghi chú, attempt và version trong lịch sử; chặn xác nhận lặp/cạnh tranh. Có màn hình danh sách và xác nhận hai bước tại `/station/parcels`.

### Kết quả thực chạy ngày 17/11/2026
- Backend riêng 4.5: **21/21 đạt**, không bỏ qua.
- Toàn backend: **419 tổng, 410 đạt, 9 bỏ qua, 0 failure, 0 error**.
- Web TypeScript/Vite build: thành công.
- Chrome/Playwright + API thật: **8/8 đạt**, không có unhandled JavaScript exception.
- Môi trường: Windows/JDK 25, H2 MSSQLServer mode. **Lịch SCHEDULED là fixture có ghi rõ; nghiệp vụ nhận kiện dùng API thật.**

### Đã test những gì — backend
| Ca | Kết quả mong đợi đã kiểm chứng |
| --- | --- |
| Nhận đúng kiện/attempt tại trạm gửi | 200, PREPARING; đúng ETag, actor, giờ server, ghi chú và attempt; giữ slot ACTIVE |
| Hàng đợi và context theo phân công | Chỉ trạm gửi; trạm đích/không phân công bị chặn |
| Sai role (4 role) / không JWT | 403 / 401, không đổi dữ liệu |
| Thu hồi phân công sau khi mở đơn | Token cũ không tiếp nhận được |
| Body thiếu ID, UUID sai, note quá 500, trường lạ actorId | 400 |
| Sai kiện / sai attempt | 400 / 409 |
| Attempt RELEASED, sai hai trạm, không khớp SCHEDULE gần nhất, nhiều ACTIVE | 409, không thay đổi đơn |
| If-Match thiếu / sai / cũ | 428 / 400 / 412 |
| Đơn chưa lên lịch | 409, giữ nguyên trạng thái |
| Gửi lại sau thành công | Không thêm lịch sử; 412 với version cũ, 409 với version mới |
| Hai nhân viên nhận đồng thời (lặp 3 lần) | Một 200, một 412; chỉ một RECEIVE |
| Transaction rollback | Hoàn nguyên cả trạng thái và lịch sử |

### Đã test trực tiếp trên web
- [x] Operator đăng nhập và mở đúng hàng đợi trạm gửi.
- [x] Xem người gửi/kiện/lần giao; checkbox bắt buộc; quay lại ở xác nhận chưa lưu.
- [x] API đầu vào sai trả 400/409/412 mà không đổi đơn.
- [x] Nhận kiện qua UI, reload vẫn giữ PREPARING/ghi chú/lịch sử.
- [x] Hai tab cùng nhận: tab sau báo xung đột; chỉ một lịch sử.
- [x] Operator trạm đích không truy cập context/hàng đợi trạm gửi.
- [x] Khách xem được RECEIVE trong lịch sử nhưng không nhận kiện được.
- [x] Đơn đã nhận rời hàng đợi; không có unhandled JavaScript error.

### Tài liệu trong repo/VS Code
- [API và cách triển khai](https://github.com/mytrongcomvn789-lab/java_project/blob/4.5-station-package-receipt/docs/05-api/station-receipt-api.md)
- [Bảng test case chi tiết](https://github.com/mytrongcomvn789-lab/java_project/blob/4.5-station-package-receipt/docs/07-testing/APDDMP-34-test-cases.md)
- [Báo cáo, ảnh và JSON kết quả](https://github.com/mytrongcomvn789-lab/java_project/blob/4.5-station-package-receipt/docs/07-testing/APDDMP-34-report.md)

### Lệnh nhóm chạy lại
```powershell
# backend
mvn -B -Dtest=OrderReceiptIntegrationTest test
mvn -B verify
# web
npm ci
npm run build
```

### Còn chờ nghiệm thu tích hợp
- [ ] API lịch thật 4.2 → nhận kiện 4.5 (không dùng fixture).
- [ ] Realtime 4.3 sau khi tích hợp nhánh.
- [ ] Chạy các ca liên quan trên SQL Server thật.
- [ ] MinIO/ảnh thật nếu nằm trong đợt review.
- [ ] Thành viên review độc lập kiểm tra và xác nhận.

9 test cũ bị bỏ qua gồm 7 test MinIO chưa có cấu hình và 2 test OrderIntegrationTest thiếu ORDER_TEST_DATABASE_URL. Không tính chúng là đạt. Không đóng issue chỉ dựa vào số test local; giữ mở để nhóm kiểm tra phần tích hợp.
