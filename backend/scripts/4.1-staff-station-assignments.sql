-- SQL Server. Chọn đúng database SmartDrone trước khi chạy.
-- Chỉ bổ sung schema, KHÔNG tự cấp quyền cho bất kỳ tài khoản nào.
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID(N'dbo.staff_station_assignments', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.staff_station_assignments (
        id uniqueidentifier NOT NULL PRIMARY KEY,
        staff_id uniqueidentifier NOT NULL,
        station_id uniqueidentifier NOT NULL,
        assigned_at datetimeoffset(6) NOT NULL,
        CONSTRAINT uq_staff_station_assignment UNIQUE (staff_id, station_id),
        CONSTRAINT fk_assignment_staff FOREIGN KEY (staff_id) REFERENCES dbo.users(id),
        CONSTRAINT fk_assignment_station FOREIGN KEY (station_id) REFERENCES dbo.stations(id)
    );
END;

-- Giữ nguyên lý do tiếng Việt khi dùng SQL Server.
IF EXISTS (SELECT 1 FROM sys.columns
           WHERE object_id = OBJECT_ID(N'dbo.order_status_history')
           AND name = N'reason' AND system_type_id = TYPE_ID(N'varchar'))
    ALTER TABLE dbo.order_status_history ALTER COLUMN reason nvarchar(500) NULL;

COMMIT TRANSACTION;
