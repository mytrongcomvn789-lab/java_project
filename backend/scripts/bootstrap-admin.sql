-- Chỉ dùng bởi quản trị DB để cấp ADMIN đầu tiên trên môi trường dev.
-- psql -v admin_email='email-da-dang-ky@example.com' -f backend/scripts/bootstrap-admin.sql
-- Không chứa mật khẩu, không tạo user mới, không cấp thêm khi đã có ADMIN đang hoạt động.
\set ON_ERROR_STOP on
BEGIN;
SELECT id FROM roles WHERE name = 'SYSTEM_ADMIN' FOR UPDATE;
WITH promoted AS (
    UPDATE users u
    SET role_id = r.id, access_version = u.access_version + 1
    FROM roles r
    WHERE r.name = 'SYSTEM_ADMIN'
      AND u.email = lower(trim(:'admin_email'))
      AND u.enabled = true
      AND NOT EXISTS (
          SELECT 1 FROM users existing
          JOIN roles er ON er.id = existing.role_id
          WHERE existing.enabled = true AND er.name = 'SYSTEM_ADMIN'
      )
    RETURNING u.id
), revoked AS (
    UPDATE refresh_tokens t
    SET revoked_at = CURRENT_TIMESTAMP
    FROM promoted p
    WHERE t.user_id = p.id AND t.revoked_at IS NULL
    RETURNING t.id
)
SELECT (SELECT count(*) FROM promoted) AS promoted_accounts,
       (SELECT count(*) FROM revoked) AS revoked_sessions;
COMMIT;
