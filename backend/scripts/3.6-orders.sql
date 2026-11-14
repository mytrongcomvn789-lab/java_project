-- Task 3.6: optional SQL Server schema setup. Hibernate ddl-auto=update creates these locally.
-- Do not run on production without the project's migration procedure.
IF OBJECT_ID(N'dbo.orders', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.orders (
        id uniqueidentifier NOT NULL CONSTRAINT pk_orders PRIMARY KEY,
        version bigint NOT NULL,
        customer_id uniqueidentifier NOT NULL,
        status nvarchar(20) NOT NULL,
        pickup_station_id uniqueidentifier NOT NULL,
        dropoff_station_id uniqueidentifier NOT NULL,
        recipient_name nvarchar(120) NOT NULL,
        recipient_phone nvarchar(20) NOT NULL,
        recipient_address_line nvarchar(500) NOT NULL,
        recipient_latitude float NOT NULL,
        recipient_longitude float NOT NULL,
        created_at datetime2(7) NOT NULL,
        updated_at datetime2(7) NOT NULL
    );
    CREATE INDEX ix_orders_customer_created ON dbo.orders (customer_id, created_at);
    CREATE INDEX ix_orders_status ON dbo.orders (status);
END;

-- Optional foreign keys (apply only after resolving legacy orders with missing parent rows):
-- ALTER TABLE dbo.order_packages ADD CONSTRAINT fk_order_packages_order FOREIGN KEY (order_id) REFERENCES dbo.orders(id);
-- ALTER TABLE dbo.order_status_history ADD CONSTRAINT fk_order_status_history_order FOREIGN KEY (order_id) REFERENCES dbo.orders(id);

IF OBJECT_ID(N'dbo.idempotency_keys', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.idempotency_keys (
        id uniqueidentifier NOT NULL CONSTRAINT pk_idempotency_keys PRIMARY KEY,
        user_id uniqueidentifier NOT NULL,
        idem_key nvarchar(100) NOT NULL,
        request_hash nvarchar(64) NOT NULL,
        order_id uniqueidentifier,
        created_at datetime2(7) NOT NULL,
        CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, idem_key)
    );
END;
