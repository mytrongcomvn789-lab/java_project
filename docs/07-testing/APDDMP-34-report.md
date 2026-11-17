# APDDMP-34 — Kết quả triển khai và kiểm thử 4.5

Ngày kiểm tra: **17/11/2026**, giờ Việt Nam. Nhánh: `4.5-station-package-receipt`, nền main `3421e2d`.

## Kết quả

| Kiểm tra thực chạy | Kết quả |
| --- | --- |
| Maven verify toàn backend | **419 tổng: 410 đạt, 9 bỏ qua, 0 failure, 0 error; BUILD SUCCESS** |
| OrderReceiptIntegrationTest | **21/21 đạt**, không bỏ qua |
| Web TypeScript + Vite build | Thành công |
| Chrome/Playwright, API thật trên local | **8/8 đạt**, không có unhandled JavaScript error |

9 test cũ bỏ qua gồm 7 test MinIO chưa có dịch vụ/cấu hình và 2 test cạnh tranh OrderIntegrationTest chưa có ORDER_TEST_DATABASE_URL. Không tính là đã đạt. Riêng 3 lần cạnh tranh nhận kiện của 4.5 có chạy và đạt.

## Đã làm gì

- Danh sách đơn SCHEDULED giới hạn theo trạm gửi được phân công, có lọc/phân trang.
- Ngữ cảnh nhận kiện gồm chi tiết đơn, tên người gửi, mã kiện và lần giao.
- API POST /api/v1/orders/{id}/receipts kiểm STATION_OPERATOR, trạm gửi, attempt ACTIVE khớp lịch, kiện thuộc đơn, If-Match và ghi chú tối đa 500 ký tự.
- RECEIVE chuyển SCHEDULED → PREPARING bằng state machine chung; lưu actor, giờ server, ghi chú, attempt trong lịch sử cùng transaction.
- Version và lịch sử duy nhất chặn thao tác lặp/cạnh tranh; rollback hoàn nguyên dữ liệu.
- React có đối chiếu kiện, xác nhận hai bước, quay lại trước khi lưu, lịch sử nhận và xử lý lỗi/xung đột.
- Thêm quyền cho hai GET và cập nhật ma trận RBAC theo controller thật. Không thay schema và không thêm nguồn trạng thái thứ hai.

## 21 test backend riêng 4.5

Nhận thành công và kiểm audit/ETag; danh sách/context theo trạm gửi; 4 vai trò sai bị chặn; chưa đăng nhập; thu hồi phân công; body sai và trường lạ; sai kiện/attempt; attempt RELEASED; attempt khác trạm; attempt không khớp lịch; nhiều attempt ACTIVE; thiếu/sai/cũ If-Match; đơn chưa lên lịch; gửi lặp và rời hàng đợi; 3 lần cạnh tranh hai nhân viên; rollback cả trạng thái/lịch sử.

## 8 kiểm tra trình duyệt trực tiếp

1. Đăng nhập nhân viên trạm gửi, xem đúng 3 đơn chờ nhận trên dữ liệu thử.
2. Xem tên người gửi, kiện và lần giao; chưa đánh dấu đối chiếu thì không gửi được; quay lại ở bước xác nhận chưa làm thay đổi đơn.
3. Gọi API với mã kiện sai / lần giao sai / version cũ: lần lượt 400 / 409 / 412, không đổi đơn.
4. Nhận kiện qua form thật, tải lại thấy PREPARING, ghi chú và lịch sử vẫn được lưu.
5. Hai tab cùng mở một đơn: tab sau báo xung đột; tải lại chỉ có một lần nhận.
6. Nhân viên chỉ thuộc trạm đích không thấy hàng đợi trạm gửi và không mở được ngữ cảnh nhận.
7. Khách hàng thấy lịch sử RECEIVE nhưng bị chặn màn hình nhận kiện.
8. Hai đơn đã nhận rời hàng đợi; còn một đơn chưa nhận để người dùng tự thử; không có exception JavaScript chưa xử lý.

[Ảnh đã nhận kiện](APDDMP-34-evidence/03-received.png) · [Ảnh tab cũ bị chặn](APDDMP-34-evidence/04-stale-tab.png)

Bằng chứng máy đọc: [browser-results.json](APDDMP-34-evidence/browser-results.json), [backend-test-suites.json](APDDMP-34-evidence/backend-test-suites.json), [API đầu vào sai](APDDMP-34-evidence/api-negative-results.json).

## Cách mở demo

Web local: http://localhost:5173/station/parcels

Tài khoản dữ liệu thử: `op@test.com`, mật khẩu demo `Passw0rd1`. Đây là tài khoản H2 local, không phải tài khoản production.

Chọn đơn còn lại → đối chiếu kiện → nhập ghi chú → đánh dấu đã kiểm tra → Tiếp tục nhận kiện → Xác nhận nhận kiện. Sau thành công, tải lại để kiểm tra lịch sử.

Backend: http://127.0.0.1:5000/api/swagger-ui/index.html

Dữ liệu H2 mất khi dừng/khởi động lại backend theo profile create-drop. Các server được để chạy tại thời điểm bàn giao; link local chỉ có tác dụng khi chúng còn hoạt động trên máy này.

## Giới hạn tích hợp

- **API nhận kiện là thật; lịch SCHEDULED trong demo là fixture có ghi rõ trong lịch sử.** Main chưa có API 4.2 hoàn chỉnh tạo lịch. Không tuyên bố đã nghiệm thu luồng lên lịch thật.
- CSDL test: H2 ở chế độ MSSQLServer, chưa chạy SQL Server thật.
- 4.3 realtime chưa merge vào main; 4.5 dùng OrderTransitionService để tương thích nguồn event chung khi tích hợp. Chưa kiểm chứng realtime của nhánh 4.3.
- Chưa triển khai 4.4, 4.7 hoặc mobile 6.4. Không coi nhận kiện tại trạm gửi là giao thành công cho khách.
- Cần kiểm tra tích hợp với lịch thật từ 4.2 và nhánh 4.3 trước khi nghiệm thu toàn quy trình.

## Tài liệu và tái chạy

[Hợp đồng API và bảng file thay đổi](../05-api/station-receipt-api.md).

```powershell
# Từ thư mục backend
mvn -B verify
# Chỉ test riêng 4.5
mvn -B -Dtest=OrderReceiptIntegrationTest test
# Từ thư mục web
npm run build
```

Log đầy đủ, script seed và kịch bản browser nằm tại `C:\Users\Cong Nha\Documents\Codex\APDDMP-34-qa`. Script seed chỉ dành H2 demo riêng, không chạy trên dữ liệu thật. Không đưa JWT, mật khẩu tài khoản thật hoặc log backend đầy đủ vào repository.


[Bảng test case chi tiết và checklist review](APDDMP-34-test-cases.md).
