-- Task 4.6 (SQL Server): thêm trạng thái MAINTENANCE và bảng lịch sử đổi trạng thái trạm.
-- Chạy lại nhiều lần an toàn. Cần chạy trên database đã tạo bảng stations từ trước,
-- vì ddl-auto=update không đổi ràng buộc CHECK của cột status.
IF OBJECT_ID(N'dbo.stations', N'U') IS NOT NULL
BEGIN
    DECLARE @drop nvarchar(max) = N'';
    SELECT @drop += N'ALTER TABLE dbo.stations DROP CONSTRAINT ' + QUOTENAME(name) + N';'
    FROM sys.check_constraints
    WHERE parent_object_id = OBJECT_ID(N'dbo.stations')
      AND definition LIKE N'%status%' AND definition LIKE N'%ACTIVE%';
    EXEC sys.sp_executesql @drop;
    ALTER TABLE dbo.stations ADD CONSTRAINT ck_stations_status
        CHECK (status IN (N'ACTIVE', N'MAINTENANCE', N'INACTIVE'));
END;

IF OBJECT_ID(N'dbo.station_status_events', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.station_status_events (
        id uniqueidentifier NOT NULL CONSTRAINT pk_station_status_events PRIMARY KEY,
        station_id uniqueidentifier NOT NULL,
        from_status nvarchar(20) NOT NULL,
        to_status nvarchar(20) NOT NULL,
        actor_id uniqueidentifier NOT NULL,
        actor_role nvarchar(30) NOT NULL,
        occurred_at datetime2(7) NOT NULL
    );
    CREATE INDEX ix_station_status_events_station ON dbo.station_status_events (station_id, occurred_at);
END;
