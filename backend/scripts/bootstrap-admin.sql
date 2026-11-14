-- Chỉ dùng bởi quản trị DB để cấp SYSTEM_ADMIN đầu tiên trên môi trường dev.
-- Sửa @admin_email trước khi chạy bằng sqlcmd; script không chứa mật khẩu.
-- Không tạo user mới, không cấp thêm khi đã có SYSTEM_ADMIN đang hoạt động.
DECLARE @admin_email nvarchar(150) = N'email-da-dang-ky@example.com';
DECLARE @role_id uniqueidentifier;
DECLARE @admin_id uniqueidentifier;
DECLARE @promoted int = 0;
DECLARE @revoked int = 0;

BEGIN TRANSACTION;

SELECT @role_id = id
FROM dbo.roles WITH (UPDLOCK, HOLDLOCK)
WHERE name = N'SYSTEM_ADMIN';

SELECT TOP (1) @admin_id = u.id
FROM dbo.users u WITH (UPDLOCK, HOLDLOCK)
WHERE LOWER(LTRIM(RTRIM(u.email))) = LOWER(LTRIM(RTRIM(@admin_email)))
  AND u.enabled = 1
  AND @role_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM dbo.users existing WITH (UPDLOCK, HOLDLOCK)
      JOIN dbo.roles existing_role ON existing_role.id = existing.role_id
      WHERE existing.enabled = 1 AND existing_role.name = N'SYSTEM_ADMIN'
  );

IF @admin_id IS NOT NULL
BEGIN
    UPDATE dbo.users
    SET role_id = @role_id, access_version = access_version + 1
    WHERE id = @admin_id;
    SET @promoted = @@ROWCOUNT;

    UPDATE dbo.refresh_tokens
    SET revoked_at = SYSUTCDATETIME()
    WHERE user_id = @admin_id AND revoked_at IS NULL;
    SET @revoked = @@ROWCOUNT;
END;

COMMIT TRANSACTION;

SELECT @promoted AS promoted_accounts, @revoked AS revoked_sessions;
