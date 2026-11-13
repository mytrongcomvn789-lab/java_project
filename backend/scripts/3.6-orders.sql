-- Task 3.6: schema tùy chọn cho PostgreSQL dev. Hibernate ddl-auto=update đã tự tạo bảng này khi chạy local.
-- Không chạy trên môi trường thật khi chưa qua quy trình migration của dự án.
CREATE TABLE IF NOT EXISTS orders (
    id uuid PRIMARY KEY,
    version bigint NOT NULL,
    customer_id uuid NOT NULL,
    status varchar(20) NOT NULL,
    pickup_station_id uuid NOT NULL,
    dropoff_station_id uuid NOT NULL,
    recipient_name varchar(120) NOT NULL,
    recipient_phone varchar(20) NOT NULL,
    recipient_address_line varchar(500) NOT NULL,
    recipient_latitude double precision NOT NULL,
    recipient_longitude double precision NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_orders_customer_created ON orders (customer_id, created_at);
CREATE INDEX IF NOT EXISTS ix_orders_status ON orders (status);

-- Khóa ngoại về orders (chạy MỘT LẦN, sau khi đã dọn dữ liệu thử không có đơn tương ứng):
--   ALTER TABLE order_packages ADD CONSTRAINT fk_order_packages_order FOREIGN KEY (order_id) REFERENCES orders(id);
--   ALTER TABLE order_status_history ADD CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES orders(id);

CREATE TABLE IF NOT EXISTS idempotency_keys (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    idem_key varchar(100) NOT NULL,
    request_hash varchar(64) NOT NULL,
    order_id uuid,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, idem_key)
);