-- SQL Server. Chọn đúng database SmartDrone trước khi chạy. Chạy lại nhiều lần không sao.
-- Task 4.2: bảng lần lên lịch giao hàng (mỗi dòng giữ chỗ ở trạm gửi và trạm nhận).
SET XACT_ABORT ON;
BEGIN TRANSACTION;

IF OBJECT_ID(N'dbo.delivery_attempts', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.delivery_attempts (
        id uniqueidentifier NOT NULL CONSTRAINT pk_delivery_attempts PRIMARY KEY,
        order_id uniqueidentifier NOT NULL,
        attempt_no int NOT NULL,
        pickup_station_id uniqueidentifier NOT NULL,
        dropoff_station_id uniqueidentifier NOT NULL,
        slot_start datetimeoffset(6) NOT NULL,
        slot_end datetimeoffset(6) NOT NULL,
        status nvarchar(20) NOT NULL,
        created_by uniqueidentifier NOT NULL,
        created_at datetimeoffset(6) NOT NULL,
        released_at datetimeoffset(6) NULL,
        CONSTRAINT uq_delivery_attempt_order_no UNIQUE (order_id, attempt_no),
        CONSTRAINT ck_delivery_attempt_status CHECK (status IN (N'ACTIVE', N'RELEASED')),
        CONSTRAINT ck_delivery_attempt_slot CHECK (slot_end > slot_start),
        CONSTRAINT fk_delivery_attempt_order FOREIGN KEY (order_id) REFERENCES dbo.orders(id),
        CONSTRAINT fk_delivery_attempt_pickup FOREIGN KEY (pickup_station_id) REFERENCES dbo.stations(id),
        CONSTRAINT fk_delivery_attempt_dropoff FOREIGN KEY (dropoff_station_id) REFERENCES dbo.stations(id),
        CONSTRAINT fk_delivery_attempt_creator FOREIGN KEY (created_by) REFERENCES dbo.users(id)
    );
    CREATE INDEX idx_delivery_attempt_pickup_slot ON dbo.delivery_attempts (pickup_station_id, slot_start);
    CREATE INDEX idx_delivery_attempt_dropoff_slot ON dbo.delivery_attempts (dropoff_station_id, slot_start);
END;

COMMIT TRANSACTION;