-- APDDMP-22: optional SQL Server schema setup.
-- Hibernate ddl-auto=update already creates this table during normal local startup.
-- Do not run this script against production without the project's migration procedure.
IF OBJECT_ID(N'dbo.addresses', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.addresses (
        id uniqueidentifier NOT NULL CONSTRAINT pk_addresses PRIMARY KEY,
        user_id uniqueidentifier NOT NULL,
        label nvarchar(500) NOT NULL,
        recipient_name nvarchar(500) NOT NULL,
        phone nvarchar(16) NOT NULL,
        address_line nvarchar(500) NOT NULL,
        latitude float NOT NULL,
        longitude float NOT NULL,
        deleted bit NOT NULL CONSTRAINT df_addresses_deleted DEFAULT 0,
        created_at datetime2(7) NOT NULL,
        updated_at datetime2(7) NOT NULL,
        CONSTRAINT fk_addresses_user FOREIGN KEY (user_id) REFERENCES dbo.users(id),
        CONSTRAINT ck_addresses_latitude CHECK (latitude BETWEEN -90 AND 90),
        CONSTRAINT ck_addresses_longitude CHECK (longitude BETWEEN -180 AND 180)
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_addresses_owner_active_created'
               AND object_id = OBJECT_ID(N'dbo.addresses'))
    CREATE INDEX ix_addresses_owner_active_created
        ON dbo.addresses (user_id, deleted, created_at, id);
