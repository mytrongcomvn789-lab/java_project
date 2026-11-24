-- SQL Server: thêm địa chỉ dễ đọc cho trạm (thay cho việc chỉ hiện kinh độ / vĩ độ).
-- Cột cho phép NULL nên trạm cũ vẫn hợp lệ. Chạy lại nhiều lần vẫn an toàn.
-- Với ddl-auto=update, Hibernate cũng tự thêm cột này khi backend khởi động; script dành cho DB tạo bằng tay.
SET XACT_ABORT ON;

IF COL_LENGTH(N'dbo.stations', N'address') IS NULL
    ALTER TABLE dbo.stations ADD address nvarchar(300) NULL;
GO

-- Điền địa chỉ cho trạm hiện có (sửa lại cho đúng thực tế rồi bỏ comment).
-- UPDATE dbo.stations SET address = N'12 Nguyễn Huệ, Quận 1, TP.HCM' WHERE name = N'tram a';
-- UPDATE dbo.stations SET address = N'45 Lê Lợi, Quận 1, TP.HCM'     WHERE name = N'tram b';

SELECT id, name, address, latitude, longitude FROM dbo.stations ORDER BY name;
