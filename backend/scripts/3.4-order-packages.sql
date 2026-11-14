-- Task 3.4: optional SQL Server schema setup. Hibernate ddl-auto=update creates these locally.
-- Do not run on production without the project's migration procedure.
IF OBJECT_ID(N'dbo.order_packages', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.order_packages (
        id uniqueidentifier NOT NULL CONSTRAINT pk_order_packages PRIMARY KEY,
        order_id uniqueidentifier NOT NULL,
        description nvarchar(500) NOT NULL,
        weight_grams int NOT NULL,
        length_cm float NOT NULL,
        width_cm float NOT NULL,
        height_cm float NOT NULL,
        created_at datetime2(7) NOT NULL,
        updated_at datetime2(7) NOT NULL,
        CONSTRAINT uq_order_packages_order UNIQUE (order_id),
        CONSTRAINT ck_order_packages_weight CHECK (weight_grams > 0),
        CONSTRAINT ck_order_packages_length CHECK (length_cm > 0),
        CONSTRAINT ck_order_packages_width CHECK (width_cm > 0),
        CONSTRAINT ck_order_packages_height CHECK (height_cm > 0)
    );
END;

IF OBJECT_ID(N'dbo.order_package_photos', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.order_package_photos (
        package_id uniqueidentifier NOT NULL,
        position int NOT NULL,
        file_id uniqueidentifier NOT NULL,
        CONSTRAINT pk_order_package_photos PRIMARY KEY (package_id, position),
        CONSTRAINT fk_order_package_photos_package FOREIGN KEY (package_id)
            REFERENCES dbo.order_packages(id) ON DELETE CASCADE
    );
END;
