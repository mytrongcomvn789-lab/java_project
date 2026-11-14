-- Task 3.5: optional SQL Server schema setup. Hibernate ddl-auto=update creates this locally.
-- Do not run on production without the project's migration procedure.
IF OBJECT_ID(N'dbo.order_status_history', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.order_status_history (
        id uniqueidentifier NOT NULL CONSTRAINT pk_order_status_history PRIMARY KEY,
        order_id uniqueidentifier NOT NULL,
        order_version bigint NOT NULL,
        from_status nvarchar(20),
        to_status nvarchar(20) NOT NULL,
        action nvarchar(30),
        actor_id uniqueidentifier,
        actor_role nvarchar(30),
        source nvarchar(20) NOT NULL,
        reason nvarchar(500),
        attempt_id uniqueidentifier,
        trace_id nvarchar(64),
        occurred_at datetime2(7) NOT NULL,
        CONSTRAINT uq_order_status_history_version UNIQUE (order_id, order_version)
    );
END;

IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'idx_order_status_history_order'
               AND object_id = OBJECT_ID(N'dbo.order_status_history'))
    CREATE INDEX idx_order_status_history_order ON dbo.order_status_history (order_id);
