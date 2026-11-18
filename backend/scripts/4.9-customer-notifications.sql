-- SQL Server. Chọn đúng database SmartDrone trước khi chạy. Chạy lại nhiều lần không sao.
-- Task 4.9: hộp thông báo trạng thái cho khách hàng. Hibernate ddl-auto=update cũng tự tạo bảng này khi dev.
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID(N'dbo.customer_notifications', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.customer_notifications (
        id uniqueidentifier NOT NULL CONSTRAINT pk_customer_notifications PRIMARY KEY,
        event_id uniqueidentifier NOT NULL,
        user_id uniqueidentifier NOT NULL,
        order_id uniqueidentifier NOT NULL,
        event_name nvarchar(40) NOT NULL,
        status nvarchar(20) NOT NULL,
        order_version bigint NOT NULL,
        title nvarchar(150) NOT NULL,
        body nvarchar(500) NOT NULL,
        created_at datetime2(7) NOT NULL,
        read_at datetime2(7) NULL,
        CONSTRAINT uq_customer_notifications_event UNIQUE (event_id)
    );
    CREATE INDEX ix_customer_notifications_user_created
        ON dbo.customer_notifications (user_id, created_at);
END;

COMMIT TRANSACTION;