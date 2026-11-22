-- Task 5.1: chỉ mục cho API thống kê (đơn theo ngày hoàn tất, số lần giao theo ngày tạo).
-- Hibernate ddl-auto=update tự tạo khi dev; script này dành cho SQL Server đã có sẵn dữ liệu. Chạy lại nhiều lần an toàn.
-- Thống kê theo ngày tạo đơn tái dùng ix_orders_pickup_created / ix_orders_dropoff_created (3.7), không thêm chỉ mục cho orders.
IF OBJECT_ID(N'dbo.order_status_history', N'U') IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_order_status_history_to_status_time'
                   AND object_id = OBJECT_ID(N'dbo.order_status_history'))
BEGIN
    CREATE INDEX ix_order_status_history_to_status_time
        ON dbo.order_status_history (to_status, occurred_at);
END;

IF OBJECT_ID(N'dbo.delivery_attempts', N'U') IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = N'ix_delivery_attempt_created'
                   AND object_id = OBJECT_ID(N'dbo.delivery_attempts'))
BEGIN
    CREATE INDEX ix_delivery_attempt_created
        ON dbo.delivery_attempts (created_at);
END;
