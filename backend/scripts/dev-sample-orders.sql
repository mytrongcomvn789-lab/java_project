-- Dữ liệu mẫu cho dev: đơn ở nhiều trạng thái để điều phối viên có gì để thao tác.
-- Mọi đơn mẫu có recipient_name bắt đầu bằng N'[MẪU]' nên dọn được bằng:
--   DELETE FROM dbo.delivery_attempts WHERE order_id IN (SELECT id FROM dbo.orders WHERE recipient_name LIKE N'[[]MẪU]%');
--   DELETE FROM dbo.order_status_history WHERE order_id IN (SELECT id FROM dbo.orders WHERE recipient_name LIKE N'[[]MẪU]%');
--   DELETE FROM dbo.order_packages WHERE order_id IN (SELECT id FROM dbo.orders WHERE recipient_name LIKE N'[[]MẪU]%');
--   DELETE FROM dbo.orders WHERE recipient_name LIKE N'[[]MẪU]%';
SET XACT_ABORT ON;
IF EXISTS (SELECT 1 FROM dbo.orders WHERE recipient_name LIKE N'[[]MẪU]%') THROW 50001, N'Dữ liệu mẫu đã tồn tại.', 1;

DECLARE @cust uniqueidentifier = (SELECT TOP 1 u.id FROM dbo.users u JOIN dbo.roles r ON r.id=u.role_id WHERE r.name='CUSTOMER' AND u.enabled=1 ORDER BY u.email);
DECLARE @disp uniqueidentifier = (SELECT id FROM dbo.users WHERE email='disp@test.com');
DECLARE @st TABLE (n int, id uniqueidentifier);
INSERT @st SELECT ROW_NUMBER() OVER (ORDER BY name), id FROM dbo.stations;
DECLARE @a uniqueidentifier=(SELECT id FROM @st WHERE n=1), @b uniqueidentifier=(SELECT id FROM @st WHERE n=2), @c uniqueidentifier=(SELECT id FROM @st WHERE n=3), @d uniqueidentifier=(SELECT id FROM @st WHERE n=4);

DECLARE @o TABLE (k int, status varchar(30), pu uniqueidentifier, dr uniqueidentifier, ageh int, ver int, att varchar(10), slotoff int);
INSERT @o VALUES
 (1,'PENDING',@a,@b,2,0,NULL,NULL),
 (2,'PENDING',@b,@c,5,0,NULL,NULL),
 (3,'PENDING',@c,@a,9,0,NULL,NULL),
 (4,'APPROVED',@a,@c,20,1,NULL,NULL),
 (5,'APPROVED',@d,@b,26,1,NULL,NULL),
 (6,'REJECTED',@b,@a,30,1,NULL,NULL),
 (7,'SCHEDULED',@a,@d,40,2,'ACTIVE',180),
 (8,'SCHEDULED',@b,@c,41,2,'ACTIVE',300),
 (9,'PREPARING',@c,@b,45,3,'ACTIVE',30),
 (10,'IN_TRANSIT',@a,@c,48,4,'ACTIVE',-10),
 (11,'AWAITING_PICKUP',@b,@a,50,5,'ACTIVE',-40),
 (12,'DELIVERED',@a,@b,72,6,'ACTIVE',-1500),
 (13,'FAILED',@c,@d,60,5,'ACTIVE',-120),
 (14,'RETURNING',@d,@a,70,6,'RELEASED',-600);

DECLARE @ids TABLE (k int, id uniqueidentifier DEFAULT NEWID());
INSERT @ids(k) SELECT k FROM @o;

BEGIN TRANSACTION;
INSERT dbo.orders(id,created_at,customer_id,dropoff_station_id,pickup_station_id,recipient_address_line,recipient_latitude,recipient_longitude,recipient_name,recipient_phone,status,updated_at,version)
SELECT i.id, DATEADD(hour,-o.ageh,SYSDATETIMEOFFSET()), @cust, o.dr, o.pu, N'Số '+CAST(o.k AS nvarchar)+N' Nguyễn Huệ, Quận 1, TP.HCM', 10.7769, 106.7009, N'[MẪU] Người nhận '+CAST(o.k AS nvarchar), '09012345'+RIGHT('00'+CAST(o.k AS varchar),2), o.status, DATEADD(hour,-o.ageh+1,SYSDATETIMEOFFSET()), o.ver
FROM @o o JOIN @ids i ON i.k=o.k;

INSERT dbo.order_packages(id,created_at,description,height_cm,length_cm,order_id,updated_at,weight_grams,width_cm)
SELECT NEWID(), SYSDATETIMEOFFSET(), N'Hộp thuốc / tài liệu #'+CAST(o.k AS nvarchar), 10, 20, i.id, SYSDATETIMEOFFSET(), 500+o.k*100, 15
FROM @o o JOIN @ids i ON i.k=o.k;

INSERT dbo.order_status_history(id,action,actor_id,actor_role,attempt_id,from_status,occurred_at,order_id,order_version,reason,source,to_status,trace_id,reason_code)
SELECT NEWID(),NULL,@cust,'CUSTOMER',NULL,NULL,DATEADD(hour,-o.ageh,SYSDATETIMEOFFSET()),i.id,0,NULL,'API','PENDING',CAST(NEWID() AS varchar(36)),NULL
FROM @o o JOIN @ids i ON i.k=o.k;

INSERT dbo.order_status_history(id,action,actor_id,actor_role,attempt_id,from_status,occurred_at,order_id,order_version,reason,source,to_status,trace_id,reason_code)
SELECT NEWID(),CASE WHEN o.status='REJECTED' THEN 'REJECT' ELSE 'APPROVE' END,@disp,'DISPATCHER',NULL,'PENDING',DATEADD(hour,-o.ageh+1,SYSDATETIMEOFFSET()),i.id,1,
 CASE WHEN o.status='REJECTED' THEN N'Địa chỉ nhận chưa rõ ràng' END,'API',CASE WHEN o.status='REJECTED' THEN 'REJECTED' ELSE 'APPROVED' END,CAST(NEWID() AS varchar(36)),NULL
FROM @o o JOIN @ids i ON i.k=o.k WHERE o.ver>=1;

INSERT dbo.delivery_attempts(id,attempt_no,created_at,created_by,dropoff_station_id,order_id,pickup_station_id,released_at,slot_end,slot_start,status)
SELECT NEWID(),1,DATEADD(hour,-o.ageh+2,SYSDATETIMEOFFSET()),@disp,o.dr,i.id,o.pu,
 CASE WHEN o.att='RELEASED' THEN SYSDATETIMEOFFSET() END,
 DATEADD(minute,o.slotoff+30,SYSDATETIMEOFFSET()),DATEADD(minute,o.slotoff,SYSDATETIMEOFFSET()),o.att
FROM @o o JOIN @ids i ON i.k=o.k WHERE o.att IS NOT NULL;
COMMIT;

SELECT status, COUNT(*) n FROM dbo.orders GROUP BY status;
