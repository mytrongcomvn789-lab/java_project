-- Task 3.5: schema tùy chọn cho PostgreSQL dev. Hibernate ddl-auto=update đã tự tạo bảng này khi chạy local.
-- Không chạy trên môi trường thật khi chưa qua quy trình migration của dự án.
-- order_id chưa có khóa ngoại vì bảng orders thuộc task 3.6; 3.6 thêm FK khi có bảng đó.
CREATE TABLE IF NOT EXISTS order_status_history (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL,
    order_version bigint NOT NULL,
    from_status varchar(20),
    to_status varchar(20) NOT NULL,
    action varchar(30),
    actor_id uuid,
    actor_role varchar(30),
    source varchar(20) NOT NULL,
    reason varchar(500),
    attempt_id uuid,
    trace_id varchar(64),
    occurred_at timestamptz NOT NULL,
    CONSTRAINT uq_order_status_history_version UNIQUE (order_id, order_version)
);
CREATE INDEX IF NOT EXISTS idx_order_status_history_order ON order_status_history (order_id);