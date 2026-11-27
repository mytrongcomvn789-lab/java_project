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

## Chưa làm

Tự động điều phối drone giữa các trạm, đặt trước thiết bị sạc ở trạm đích và tính thời gian chờ cho khách hàng. Mobile chưa có màn hình này.
