import { Navigate, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "./AuthContext";

// Chưa đăng nhập → chuyển về /login (nhớ trang đang vào để quay lại sau khi đăng nhập).
// Task 2.6 có thể thêm RoleRoute (kiểm tra vai trò theo route) ngay bên trong nhóm này.
export default function RequireAuth() {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading)
    return (
      <p className="muted" style={{ padding: 40 }}>
        Đang tải...
      </p>
    );
  if (!user)
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <Outlet />;
}
