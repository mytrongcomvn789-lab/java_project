# Thiết bị sạc và drone của trạm

Mỗi trạm có 8 thiết bị sạc; drone dùng chung giữa các trạm (vị trí hiện tại nằm ở `Drone.stationId`). Mỗi thiết bị chỉ nhận một drone tại một thời điểm.

## Nguồn dữ liệu: giả lập

Chưa có phần cứng thật. Pin, thời gian sạc còn lại, giờ dự kiến xong và nhiệt độ được tính theo công thức trong `ChargingRules` (1% pin mỗi phút, mục tiêu 100%). API ghi rõ `source = SIMULATED`. Heartbeat thiết bị do `ChargerHeartbeatSimulator` tạo, chỉ bật khi `smartdrone.charging.simulate-heartbeat=true` (profile `demo`); thiết bị bảo trì không gửi heartbeat nên hiện mất kết nối.

## Trạng thái thiết bị (suy ra, không lưu)

| Trạng thái | Điều kiện | Màu trên web |
|---|---|---|
| `EMPTY` | Không có drone | Không màu |
| `CHARGING` | Có drone, pin chưa đạt mục tiêu | Vàng |
| `FULL` | Pin đã đạt mục tiêu, drone vẫn còn cắm | Xanh lá |
| `MAINTENANCE` | Đang bảo trì | Đỏ, dấu chấm than |

Drone sạc đầy chưa làm thiết bị trống: chỉ khi nhân viên trạm bấm "Cho drone rời" thì thiết bị mới về `EMPTY`.

## API

| Method | Đường dẫn | Vai trò | Mô tả |
|---|---|---|---|
| GET | `/v1/stations/{id}/chargers` | `STATION_OPERATOR` | Chi tiết từng thiết bị, drone đang cắm, drone đang đỗ, `nextSlotAt` |
| PATCH | `/v1/stations/{id}/chargers/{chargerId}/maintenance` | `STATION_OPERATOR` | Body `{"maintenance": true\|false}`. Từ chối nếu còn drone cắm |
| POST | `/v1/stations/{id}/chargers/{chargerId}/release` | `STATION_OPERATOR` | Chỉ khi thiết bị ở trạng thái `FULL` |
| GET | `/v1/stations/monitoring` | Quản lý, điều phối, nhân viên trạm | Thêm `chargers` (biểu tượng: mã, trạng thái, % pin) và `connection` lấy từ heartbeat |

Nhân viên trạm chỉ truy cập trạm được phân công; trạm khác trả 404. Mọi thao tác ghi đều có audit log (`CHARGER_DRONE_RELEASED`, `CHARGER_MAINTENANCE_ON/OFF`).

## Kết nối của trạm

`connection.state` = `ONLINE` nếu ít nhất một thiết bị có heartbeat trong 2 phút, `OFFLINE` nếu không, `UNKNOWN` (nguồn `NONE`) nếu trạm chưa có thiết bị sạc.

## Chưa làm (phần sạc)

Tự động điều phối drone giữa các trạm, đặt trước thiết bị sạc ở trạm đích và tính thời gian chờ cho khách hàng. Mobile chưa có màn hình này.

## Điều phối drone tự động

Hệ thống tự chọn drone, đặt trước thiết bị sạc ở trạm nhận và tính thời gian chờ. Mọi mốc thời gian là **ước tính theo công thức** (`source = FORMULA`): khoảng cách đường chim bay, tốc độ `app.eta.speed-kmh`, tiêu hao pin 2%/km, dự phòng 20% pin, tốc độ sạc giả lập 1%/phút.

Luồng gắn vào đơn:

1. Trạm gửi nhận kiện (đơn sang `PREPARING`): tự phân công drone.
2. Chọn drone: ưu tiên drone đang ở trạm gửi; nếu không có thì drone ở trạm gần nhất (xa dần), drone đó phải bay tới trạm gửi trước. Drone phải đủ pin cho cả chặng; drone đang sạc chưa đủ pin thì chờ sạc.
3. Trạm nhận: đặt trước thiết bị sạc nhận được sớm nhất (không bảo trì, chưa bị đơn khác đặt). Nếu hết chỗ, drone không cần đợi trống rồi mới đi mà khởi hành để đến đúng lúc thiết bị được giải phóng (lý do `DESTINATION_FULL`).
4. Đơn khởi hành: drone cất cánh, nhả thiết bị sạc đang cắm. Đơn đến trạm nhận: drone hạ cánh, cắm vào thiết bị đã đặt (hoặc thiết bị trống khác, hoặc đỗ chờ).
5. Nếu hệ thống chưa có drone nào, mọi bước trên không làm gì và luồng đơn giữ nguyên.

| Method | Đường dẫn | Vai trò | Mô tả |
|---|---|---|---|
| GET | `/v1/orders/{id}/dispatch` | Khách hàng, điều phối, nhân viên trạm, quản lý | Kế hoạch của đơn. Khách hàng chỉ thấy thời gian chờ, lý do, giờ dự kiến; drone, trạm xuất phát và thiết bị sạc là `null` |
| GET | `/v1/dispatch/board` | Điều phối, nhân viên trạm, quản lý | Đội drone và các đơn trong phạm vi trạm được phân công |
| POST | `/v1/orders/{id}/dispatch-plan` | Nhân viên trạm gửi | Phân công hoặc tính lại cho đơn `PREPARING` |

Lý do chờ (`waitReason`): `NONE`, `DRONE_CHARGING`, `DRONE_REPOSITION`, `DESTINATION_FULL`; giai đoạn `NO_DRONE` và `NO_CHARGER` khi chưa phân công được. Bộ quét định kỳ (`smartdrone.dispatch.auto-sweep=true`, profile demo) gán drone cho đơn đang chờ, tính lại kế hoạch và hủy kế hoạch của đơn không còn chờ.

Chưa làm: thông báo chủ động cho khách khi thời gian chờ đổi; điều phối dựa trên vị trí drone thật; mobile.
