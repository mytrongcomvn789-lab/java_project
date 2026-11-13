-- Task 3.7: chỉ mục cho danh sách đơn theo trạm. Hibernate ddl-auto=update đã tự tạo khi chạy local.
CREATE INDEX IF NOT EXISTS ix_orders_pickup_created  ON orders (pickup_station_id, created_at);
CREATE INDEX IF NOT EXISTS ix_orders_dropoff_created ON orders (dropoff_station_id, created_at);