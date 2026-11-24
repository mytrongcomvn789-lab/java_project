# APDDMP-39 (5.2) — Dashboard web cho Quản lý hậu cần: kiểm chứng

## 1. Phạm vi

- Trang mới `/dashboard` (menu "Dashboard", nhóm Quản lý hậu cần), chỉ `LOGISTICS_MANAGER` (route bọc `RequireRole`; server vẫn kiểm tra ở `StatisticsController`).
- File: `web/src/features/dashboard/DashboardPage.tsx`, `dashboard.css`; `web/src/components/charts/BarChart.tsx`, `charts.css` (SVG thuần, không thêm thư viện npm); `reportService.ts` (chỉ **thêm** `stationLoad`, kiểu `StationLoadStat`, `byStatus`); `navigation.ts`, `AppRouter.tsx`.
- Không sửa backend, `ReportsPage` (5.3), `/reports`, `/performance`.
- Web không tự tính công thức: mọi số lấy từ API 5.1 (`docs/05-api/statistics-api.md`). Web chỉ định dạng (phần trăm 2 chữ số, giây → "x giờ y phút") và kiểm tra bộ lọc.

## 2. API dùng

| API | Dùng cho |
|---|---|
| `GET /v1/statistics/orders/summary` | Thẻ số liệu, biểu đồ phân bố trạng thái (`byStatus`) |
| `GET /v1/statistics/orders/daily` | Biểu đồ cột chồng theo ngày |
| `GET /v1/statistics/stations/load` | Thanh tải trạm (khung giờ hiện tại, **không** theo khoảng ngày) |
| `GET /v1/stations` | Danh sách trạm cho bộ lọc |

`summary` + `daily` + `load` được gọi cùng lúc, kết quả và bộ lọc đã áp dụng được set **cùng một lần** (`data` + `applied`), nên thẻ và biểu đồ không lệch nhau.

## 3. Môi trường chạy thử

- Windows 11, JDK 21, Node 20.20.2, Chrome headless điều khiển bằng `puppeteer-core` (cài ở thư mục tạm **ngoài repo**).
- Backend: `cd backend; mvn spring-boot:run "-Dspring-boot.run.profiles=h2,demo-seed"` (H2 trong RAM). Vite dev tự chuyển sang cổng 5174 vì 5173 đang bị chiếm, nên chạy backend với `APP_CORS_ALLOWED_ORIGINS=http://localhost:5174`.
- **Dữ liệu là DỮ LIỆU MẪU**, tạo bằng một seeder tạm (`TmpDemoSeeder`, profile `demo-seed`, **không commit**): 3 trạm, tài khoản `manager/customer/dispatcher/admin@demo.test` (email giả, mật khẩu mẫu), khoảng 130 đơn rải 60 ngày (có vài ngày không có đơn), có đơn giao lại, thất bại, hoàn, hủy, từ chối; manager được phân công trạm A và B, trạm C ngoài phạm vi.
- Ngày chạy thử: 23/11/2026 (giờ Asia/Ho_Chi_Minh).

## 4. Bảng đối chiếu UI với JSON API (cùng bộ lọc)

Bộ lọc mặc định 25/10–23/11/2026, mốc CREATED, toàn phạm vi. UI lấy bằng trình duyệt thật, JSON lấy bằng gọi API trực tiếp với token manager.

| Chỉ số | UI | JSON API | Kết quả |
|---|---|---|---|
| Tổng đơn | 69 | `orders.total` = 69 | PASS |
| Đã giao | 15 | `orders.delivered` = 15 | PASS |
| Đang xử lý | 21 | `orders.inProgress` = 21 | PASS |
| Giao thất bại | 6 | `orders.failed` = 6 | PASS |
| Tỷ lệ giao thành công | 46.88% (mẫu 32) | `successRate` = 0.46875 | PASS |
| Tỷ lệ hoàn | 34.38% | `returnRate` = 0.34375 | PASS |
| Tỷ lệ giao lại | 20.00% (5/25) | `retryRate` = 0.2 | PASS |
| Thời gian giao TB | 20 phút (mẫu 12, loại 3) | `avgSeconds` = 1200, `count` = 12, `excludedOrders` = 3 | PASS |
| Phân bố trạng thái | 12 cột khớp `byStatus` (vd DELIVERED 15, SCHEDULED 9) | `byStatus` | PASS (đối chiếu bằng mắt trên ảnh chụp) |
| Số đơn theo ngày | Bảng "Xem dạng bảng": tổng mỗi ngày = `daily[i].orders.total`, đủ số dòng (30 / 366 / 1 / 10 ngày) | `daily` | PASS (tự động) |
| Tải trạm | Tram A 4/4, xử lý 0, 100.0%; Tram B 4/6, xử lý 0, 66.7% | `booked/capacity/processing/utilization` | PASS (tự động) |
| Khung giờ | 19:30–20:00 (giờ VN) | `slotStart` 12:30Z, `slotEnd` 13:00Z | PASS |

## 5. Các ca biên

Kết quả thật từ lần chạy tự động. PASS = UI khớp API / hành vi đúng như mô tả.

| Ca | Kết quả | Ghi chú |
|---|---|---|
| `from = to` (23/11) | PASS | total 7, 1 dòng ngày. Tỷ lệ giao và thời gian giao hiện "Chưa có dữ liệu" kèm "Mẫu = 0" (vì mẫu bằng 0 thật) |
| Khoảng 366 ngày | PASS | total 132, 366 dòng ngày, khớp API |
| Khoảng 367 ngày | PASS | Client chặn: "Khoảng thời gian tối đa 366 ngày (đang chọn 367 ngày)", **không gọi API**. Backend cũng trả `400 VALIDATION_ERROR` cho 367 ngày (kiểm bằng curl) |
| `from > to` | PASS | Client chặn, không gọi API. Backend trả 400 (curl) |
| Ngày không có đơn | PASS | Có dòng ngày với toàn số 0 trong bảng, khớp `daily` |
| Không có đơn nào (2020) | PASS | Hiện "Không có đơn trong khoảng đã chọn"; thẻ đếm = 0; tỷ lệ/thời gian = "Chưa có dữ liệu" + "Mẫu = 0" (không hiện 0%/0 phút) |
| Mốc COMPLETED | PASS | total 32, đã giao 15, tỷ lệ giao 71.43%, khớp API |
| Lọc một trạm (A) | PASS | Khớp API cùng `stationId` |
| Nút 7 ngày / Tháng này | PASS | 17/11→23/11 và 01/11→23/11 (theo giờ VN); "Tháng này" total 53 khớp API |
| Backend tắt | PASS | Hiện "Network Error" + nút "Thử lại", không còn thẻ/biểu đồ cũ |
| 403 từ server | PASS | Dùng token CUSTOMER gắn vào phiên: hiện "Bạn không có quyền xem dashboard" |
| Không phải LOGISTICS_MANAGER | PASS | CUSTOMER, DISPATCHER, SYSTEM_ADMIN: menu không có Dashboard; vào `/dashboard` bị đưa về `/`; không có request thống kê nào |
| Màn hình 390px | PASS | Trang không tràn ngang (`scrollWidth` 390 = `clientWidth`); biểu đồ cuộn ngang trong khung riêng |
| Tooltip khi rê | PASS | Vd "2026-10-25 / Giao thất bại: 1" |

### Kiểm quyền ở server (curl, token thật)

| Vai trò | summary | daily | stations/load |
|---|---|---|---|
| CUSTOMER | 403 | 403 | 403 |
| DISPATCHER | 403 | 403 | 403 |
| SYSTEM_ADMIN | 403 | 403 | 403 |
| LOGISTICS_MANAGER | 200 | 200 | 200 |
| Không token | 401 | | |

## 6. Điều CHƯA kiểm chứng / hạn chế

- **Chốt chặn phản hồi cũ (`requestId`) chưa được kiểm bằng E2E.** Khi đang tải, nút "Áp dụng" và nút chọn nhanh bị khóa nên giao diện không tạo được hai yêu cầu chồng nhau; thử ép hai yêu cầu không thành công. Mã có chốt chặn nhưng chưa thấy nó chạy.
- **`processing` luôn bằng 0 trong dữ liệu mẫu**: seeder tạm không tạo `order_packages`, mà 5.1 đếm theo kiện, nên chưa thấy số `processing` khác 0 và chưa so sánh với màn "Giám sát trạm" (4.6). Phần ghi chú khác biệt với 4.6 đã có trên UI nhưng chưa đối chiếu số thật.
- Chưa chạy trên SQL Server thật; chỉ H2.
- Chưa kiểm bằng trình duyệt Firefox/Safari, chưa kiểm bằng thiết bị cảm ứng thật (tooltip khi chạm chỉ mới kiểm khi rê chuột).
- Ở chế độ dev, `React.StrictMode` làm mỗi endpoint bị gọi 2 lần khi vào trang; bản build production gọi 1 lần.
- Chưa có test tự động (unit/component) trong repo cho dashboard; các kiểm thử trên là script ngoài repo.
- Ảnh chụp màn hình không đưa vào repo.

## 7. Hướng dẫn demo

1. Chạy backend H2 như mục 3 (hoặc SQL Server có dữ liệu thật). Không cần seeder nếu đã có dữ liệu thật.
2. `cd web; npm run dev`, mở địa chỉ Vite in ra.
3. Đăng nhập tài khoản `LOGISTICS_MANAGER` đã được phân công trạm.
4. Vào menu **Dashboard**: xem 8 thẻ số liệu, biểu đồ cột chồng theo ngày, phân bố trạng thái, tải trạm.
5. Đổi bộ lọc (từ/đến ngày, mốc ngày, trạm) hoặc bấm 7 ngày / 30 ngày / Tháng này rồi **Áp dụng**; thẻ và biểu đồ cập nhật cùng lúc.
6. Bấm **Xem dạng bảng** ở mỗi biểu đồ để đối chiếu từng giá trị.
7. Thử chọn khoảng 367 ngày hoặc "Từ" sau "Đến" để thấy thông báo lỗi mà không gọi API.
8. Đăng nhập vai trò khác để thấy không có menu Dashboard.
