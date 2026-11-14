-- SQL Server schema for task 3.2. Safe to run more than once.
IF OBJECT_ID(N'dbo.stations', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.stations (
        id uniqueidentifier NOT NULL CONSTRAINT pk_stations PRIMARY KEY,
        name nvarchar(200) NOT NULL,
        latitude float NOT NULL,
        longitude float NOT NULL,
        capacity int NOT NULL,
        status nvarchar(20) NOT NULL CONSTRAINT df_stations_status DEFAULT N'ACTIVE',
        CONSTRAINT ck_stations_capacity_positive CHECK (capacity > 0),
        CONSTRAINT ck_stations_latitude CHECK (latitude BETWEEN -90 AND 90),
        CONSTRAINT ck_stations_longitude CHECK (longitude BETWEEN -180 AND 180),
        CONSTRAINT ck_stations_status CHECK (status IN (N'ACTIVE', N'INACTIVE'))
    );
    CREATE INDEX ix_stations_name ON dbo.stations (name, id);
END;
