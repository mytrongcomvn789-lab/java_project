import { Navigate, Outlet } from "react-router-dom";
import { useAuth } from "./AuthContext";
import type { RoleName } from "../../types/api";

// Chặn theo vai trò ở giao diện. Đây chỉ là lớp tiện lợi: quyền thật được backend kiểm tra ở mọi API.
export default function RequireRole({ roles }: { roles: RoleName[] }) {
  const { user } = useAuth();
  if (!user || !roles.includes(user.role)) return <Navigate to="/" replace />;
  return <Outlet />;
}
