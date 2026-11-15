-- SQL Server: quản trị viên chủ động chọn điều phối viên và trạm.
-- Thay hai giá trị NULL sau bằng email/UUID thực tế. Script không đổi role tài khoản.
SET XACT_ABORT ON;
DECLARE @DispatcherEmail nvarchar(150) = NULL;
DECLARE @StationId uniqueidentifier = NULL;

IF @DispatcherEmail IS NULL OR @StationId IS NULL
    THROW 50001, N'Điền email điều phối viên và UUID trạm trước khi chạy.', 1;

BEGIN TRANSACTION;
DECLARE @StaffId uniqueidentifier;
SELECT @StaffId = u.id
FROM dbo.users u JOIN dbo.roles r ON r.id = u.role_id
WHERE u.email = @DispatcherEmail AND u.enabled = 1 AND r.name = 'DISPATCHER';

IF @StaffId IS NULL
BEGIN
    ROLLBACK TRANSACTION;
    THROW 50002, N'Không có điều phối viên đang hoạt động với email này.', 1;
END;

IF NOT EXISTS (SELECT 1 FROM dbo.stations WHERE id = @StationId)
BEGIN
    ROLLBACK TRANSACTION;
    THROW 50003, N'Không tìm thấy trạm.', 1;
END;

IF NOT EXISTS (SELECT 1 FROM dbo.staff_station_assignments WITH (UPDLOCK, HOLDLOCK)
               WHERE staff_id = @StaffId AND station_id = @StationId)
    INSERT dbo.staff_station_assignments(id, staff_id, station_id, assigned_at)
    VALUES (NEWID(), @StaffId, @StationId, SYSUTCDATETIME());

COMMIT TRANSACTION;

SELECT id, staff_id, station_id, assigned_at
FROM dbo.staff_station_assignments WHERE staff_id = @StaffId AND station_id = @StationId;
