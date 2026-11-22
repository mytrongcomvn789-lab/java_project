-- Task 2.4 (SQL Server): audit log dùng chung (NFR-07).
-- Chạy lại nhiều lần an toàn. ddl-auto=update cũng tự tạo bảng/cột khi chạy app;
-- script này để tạo sẵn trên database dùng chung hoặc khi tắt ddl-auto.
IF OBJECT_ID(N'dbo.audit_logs', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.audit_logs (
        id uniqueidentifier NOT NULL CONSTRAINT pk_audit_logs PRIMARY KEY,
        actor_id uniqueidentifier NULL,
        actor_email nvarchar(150) NULL,
        actor_role nvarchar(30) NULL,
        action nvarchar(60) NOT NULL,
        target_type nvarchar(40) NOT NULL,
        target_id nvarchar(100) NULL,
        detail nvarchar(1000) NULL,
        ip_address nvarchar(45) NULL,
        trace_id nvarchar(36) NULL,
        occurred_at datetime2(7) NOT NULL
    );
END;

-- Bảng đã có từ FR-02: bổ sung IP và traceId của request.
IF COL_LENGTH(N'dbo.audit_logs', N'ip_address') IS NULL
    ALTER TABLE dbo.audit_logs ADD ip_address nvarchar(45) NULL;
IF COL_LENGTH(N'dbo.audit_logs', N'trace_id') IS NULL
    ALTER TABLE dbo.audit_logs ADD trace_id nvarchar(36) NULL;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_audit_logs_time' AND object_id = OBJECT_ID(N'dbo.audit_logs'))
    CREATE INDEX ix_audit_logs_time ON dbo.audit_logs (occurred_at);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_audit_logs_actor' AND object_id = OBJECT_ID(N'dbo.audit_logs'))
    CREATE INDEX ix_audit_logs_actor ON dbo.audit_logs (actor_id, occurred_at);
IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_audit_logs_target' AND object_id = OBJECT_ID(N'dbo.audit_logs'))
    CREATE INDEX ix_audit_logs_target ON dbo.audit_logs (target_type, target_id, occurred_at);
