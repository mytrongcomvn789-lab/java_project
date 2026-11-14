-- Optional local demo data. The fixed IDs make the examples reusable.
IF NOT EXISTS (SELECT 1 FROM dbo.stations WHERE id = '32000000-0000-0000-0000-000000000001')
    INSERT INTO dbo.stations (id, name, latitude, longitude, capacity, status)
    VALUES ('32000000-0000-0000-0000-000000000001', N'Trạm trung tâm Quận 1', 10.7769, 106.7009, 4, N'ACTIVE');

IF NOT EXISTS (SELECT 1 FROM dbo.stations WHERE id = '32000000-0000-0000-0000-000000000002')
    INSERT INTO dbo.stations (id, name, latitude, longitude, capacity, status)
    VALUES ('32000000-0000-0000-0000-000000000002', N'Trạm Thủ Đức', 10.8505, 106.7717, 3, N'ACTIVE');
