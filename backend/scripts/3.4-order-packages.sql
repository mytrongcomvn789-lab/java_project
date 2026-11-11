-- Task 3.4: schema tùy chọn cho PostgreSQL dev. Hibernate ddl-auto=update đã tự tạo các bảng này khi chạy local.
-- Không chạy trên môi trường thật khi chưa qua quy trình migration của dự án.
-- order_id chưa có khóa ngoại vì bảng orders thuộc task 3.6; 3.6 thêm FK khi có bảng đó.
CREATE TABLE IF NOT EXISTS order_packages (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL,
    description varchar(500) NOT NULL,
    weight_grams integer NOT NULL CHECK (weight_grams > 0),
    length_cm double precision NOT NULL CHECK (length_cm > 0),
    width_cm double precision NOT NULL CHECK (width_cm > 0),
    height_cm double precision NOT NULL CHECK (height_cm > 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_order_packages_order UNIQUE (order_id)
);
CREATE TABLE IF NOT EXISTS order_package_photos (
    package_id uuid NOT NULL REFERENCES order_packages(id) ON DELETE CASCADE,
    position integer NOT NULL,
    file_id uuid NOT NULL,
    PRIMARY KEY (package_id, position)
);
