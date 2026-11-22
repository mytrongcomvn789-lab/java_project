-- SQL Server. Chọn đúng database trước khi chạy. Chạy lại nhiều lần không sao.
-- Task 5.4: API key cho hệ thống ngoài. Hibernate ddl-auto=update cũng tự tạo 2 bảng này khi dev.
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID(N'dbo.api_keys', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.api_keys (
        id uniqueidentifier NOT NULL CONSTRAINT pk_api_keys PRIMARY KEY,
        client_name nvarchar(150) NOT NULL,
        secret_hash char(64) NOT NULL,
        status nvarchar(20) NOT NULL,
        rate_limit_per_minute int NOT NULL,
        created_by uniqueidentifier NOT NULL,
        created_at datetime2(7) NOT NULL,
        expires_at datetime2(7) NULL,
        revoked_at datetime2(7) NULL,
        last_used_at datetime2(7) NULL
    );
END;

IF OBJECT_ID(N'dbo.api_key_scopes', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.api_key_scopes (
        api_key_id uniqueidentifier NOT NULL,
        scope nvarchar(30) NOT NULL,
        CONSTRAINT pk_api_key_scopes PRIMARY KEY (api_key_id, scope),
        CONSTRAINT fk_api_key_scopes_key FOREIGN KEY (api_key_id) REFERENCES dbo.api_keys(id)
    );
END;

COMMIT TRANSACTION;