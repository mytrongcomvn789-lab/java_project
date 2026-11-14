-- Task 3.7: chỉ mục cho danh sách đơn theo trạm.
IF OBJECT_ID(N'dbo.orders', N'U') IS NOT NULL
   AND NOT EXISTS (
       SELECT 1
       FROM sys.indexes
       WHERE name = N'ix_orders_pickup_created'
         AND object_id = OBJECT_ID(N'dbo.orders')
   )
BEGIN
    CREATE INDEX ix_orders_pickup_created
        ON dbo.orders (pickup_station_id, created_at);
END;

IF OBJECT_ID(N'dbo.orders', N'U') IS NOT NULL
   AND NOT EXISTS (
       SELECT 1
       FROM sys.indexes
       WHERE name = N'ix_orders_dropoff_created'
         AND object_id = OBJECT_ID(N'dbo.orders')
   )
BEGIN
    CREATE INDEX ix_orders_dropoff_created
        ON dbo.orders (dropoff_station_id, created_at);
END;