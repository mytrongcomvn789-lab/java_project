## Hướng dẫn test task 4.4

### A. Test tự động backend (bắt buộc trước khi mở PR)

```bash
cd backend
mvn test -Dtest=OrderTrackingIntegrationTest        # test riêng của 4.4 (H2 trong RAM, không cần SQL Server)
mvn test -Dtest=RbacIntegrationTest                 # ma trận quyền: dispatch/arrive = 428, tracking = 404
mvn test                                            # toàn bộ, để chắc 4.4 không làm hỏng task khác
```

Kết quả mong đợi: tất cả PASS. Dán phần tóm tắt `Tests run / Failures` vào mô tả PR. `OrderTrackingIntegrationTest` kiểm tra:

| Nhóm                  | Test                                                                                        | Mong đợi                                                                                               |
| --------------------- | ------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| Đúng/sai trạm         | `dispatchAtPickupRecordsActorAttemptTimeAndEvent`                                           | Nhân viên trạm gửi dispatch: 200, IN_TRANSIT, ETag "4", lịch sử có actor/lần giao/thời điểm, 1 sự kiện |
|                       | `dispatchWrongStationOrRoleChangesNothing`                                                  | Trạm nhận/ngoài trạm: 404; khách, điều phối: 403; không token: 401; không đổi dữ liệu                  |
|                       | `revokedAssignmentIsCheckedAgainOnWrite`                                                    | Bị gỡ phân công thì ghi bị 404                                                                         |
|                       | `arriveNeedsInTransitAndDropoffOperator`                                                    | Arrive chỉ khi IN_TRANSIT và đúng nhân viên trạm nhận                                                  |
| Chuyển sai trạng thái | `invalidRequestsAreRejected`, `releasedAttemptCannotDispatch`                               | 412 sai version, 428 thiếu If-Match, 400 body/trường lạ/ghi chú >500, 409 lần giao hết hiệu lực        |
| Trùng lặp             | `repeatedDispatchWritesOneHistoryRowAndOneEvent`                                            | Gửi lại: 412 hoặc 409; chỉ 1 dòng lịch sử, 1 sự kiện                                                   |
| Timeline              | `timelineIsOrderedSurvivesReloadAndHidesActorIds`                                           | Version 0..4 liên tục, tải lại giống hệt, không có `actorId`, `eventId` = id lịch sử                   |
| Quyền xem             | `trackingFollowsOrderScope`, `customerDoesNotSeeInternalNotes`                              | Khách khác/nhân viên ngoài trạm: 404; khách không thấy ghi chú nội bộ                                  |
| Thao tác hiện nút     | `availableActionsFollowStationAndStatus`                                                    | Trạm gửi thấy DISPATCH, trạm nhận thấy ARRIVE sau khi xuất phát                                        |
| Bản đồ                | `positionIsSimulatedOnlyInFlightAndNoLiveGps`, `missingStationIsReportedWithoutCoordinates` | `liveGpsAvailable=false`, mô phỏng chỉ khi IN_TRANSIT, trạm thiếu thì toạ độ null                      |

### B. Build web

```bash
cd web
npm install
npm run build        # tsc --noEmit + vite build, phải không có lỗi
```

### C. Demo thủ công với API thật (H2, dữ liệu mất khi tắt app)

1. Chạy backend: `cd backend` rồi `mvn spring-boot:run "-Dspring-boot.run.profiles=h2"` (cổng 5000, context `/api`). Chạy web: `cd web && npm run dev` (http://localhost:5173).
2. Chuẩn bị dữ liệu: đăng ký tài khoản khách; tạo/cấp vai trò DISPATCHER và hai STATION_OPERATOR (admin cấp qua `PATCH /v1/users/{id}/access`, xem `rbac-demo.http`); tạo hai trạm (`POST /v1/stations` bằng LOGISTICS_MANAGER); phân công mỗi nhân viên một trạm bằng bảng `staff_station_assignments` (cần chèn trực tiếp, chưa có API).
3. Đi đơn tới PREPARING: khách tạo đơn → điều phối duyệt (4.1) → lên lịch (4.2) → nhân viên trạm gửi nhận kiện (4.5). **Dữ liệu từ các bước 4.1–4.5 là fixture của nhóm; nếu chưa merge thì ghi rõ trong PR.**
4. Đăng nhập nhân viên **trạm gửi**, mở chi tiết đơn: thấy nút _Xác nhận xuất phát_ → bấm. Đơn thành Đang giao, timeline thêm bước, sơ đồ có marker giữa tuyến ghi "MÔ PHỎNG".
5. Đăng nhập nhân viên **trạm nhận**: thấy nút _Xác nhận drone đã đến trạm_ → bấm. Đơn thành Chờ nhận hàng.
6. Kiểm tra bằng curl/REST Client (thay token, id):

```
GET  /api/v1/orders/{id}/tracking                       -> 200, timeline tăng dần
POST /api/v1/orders/{id}/dispatch  (If-Match: "3")      -> 200
POST /api/v1/orders/{id}/dispatch  (If-Match: "3")      -> 412 (gửi lại)
POST /api/v1/orders/{id}/arrive    (nhân viên trạm gửi) -> 404 (sai đầu)
GET  /api/v1/orders/{id}/tracking  (khách khác)         -> 404
```

7. Các tình huống giao diện: F5 trang (timeline vẫn đủ), tắt backend (hiện cảnh báo mất kết nối, giữ dữ liệu cũ), bật lại (tự tải lại sau tối đa 10 giây), trạm thiếu toạ độ (báo thiếu toạ độ, không vẽ tuyến).
8. Không đưa token, mật khẩu hay dữ liệu cá nhân vào ảnh/PR.
