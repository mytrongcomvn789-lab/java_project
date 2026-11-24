-- SQL Server: gán nhân viên (điều phối, nhân viên trạm, quản lý hậu cần) vào trạm để họ thấy đơn và số liệu.
-- Phạm vi dữ liệu của mọi API (đơn, giám sát trạm, dashboard) lấy từ bảng staff_station_assignments.
-- Không có dòng gán thì danh sách đơn và dashboard luôn rỗng, dù DB có đơn.
--
-- Cách dùng: sửa danh sách @Emails và @StationNames bên dưới rồi chạy. Chạy lại nhiều lần vẫn an toàn.
-- Chỉ gán cho tài khoản đang hoạt động và có vai trò nhân viên; không đổi vai trò của ai.
SET XACT_ABORT ON;

DECLARE @Emails TABLE (email nvarchar(150) PRIMARY KEY);
INSERT @Emails(email) VALUES
    (N'haucan@example.com');
    -- (N'dieuphoi@example.com'),
    -- (N'tram@example.com');

-- Để trống = gán vào TẤT CẢ trạm. Điền tên để chỉ gán vào các trạm đó.
DECLARE @StationNames TABLE (name nvarchar(200) PRIMARY KEY);
-- INSERT @StationNames(name) VALUES (N'tram a'), (N'tram b');

BEGIN TRANSACTION;

INSERT dbo.staff_station_assignments(id, staff_id, station_id, assigned_at)
SELECT NEWID(), u.id, s.id, SYSUTCDATETIME()
FROM dbo.users u
JOIN dbo.roles r ON r.id = u.role_id
CROSS JOIN dbo.stations s
WHERE u.email IN (SELECT email FROM @Emails)
  AND u.enabled = 1
  AND r.name IN ('DISPATCHER', 'STATION_OPERATOR', 'LOGISTICS_MANAGER')
  AND (NOT EXISTS (SELECT 1 FROM @StationNames) OR s.name IN (SELECT name FROM @StationNames))
  AND NOT EXISTS (SELECT 1 FROM dbo.staff_station_assignments x
                  WHERE x.staff_id = u.id AND x.station_id = s.id);

COMMIT TRANSACTION;

-- Kiểm tra: ai đang được gán vào trạm nào.
SELECT u.email, r.name AS role, s.name AS station_name
FROM dbo.staff_station_assignments a
JOIN dbo.users u ON u.id = a.staff_id
LEFT JOIN dbo.roles r ON r.id = u.role_id
JOIN dbo.stations s ON s.id = a.station_id
ORDER BY u.email, s.name;
