import type { ReactNode } from "react";
import { useAuth } from "../auth/AuthContext";

export default function CustomerGate({ children }: { children: ReactNode }) {
  const { user, isDemo } = useAuth();
  if (isDemo) return <div className="card customer-notice" role="status">
    Hãy đăng nhập bằng tài khoản thật để lưu và sử dụng địa chỉ giao hàng.
  </div>;
  if (user?.role !== "CUSTOMER") return <div className="card customer-notice" role="alert">
    Sổ địa chỉ dành cho tài khoản khách hàng.
  </div>;
  return <>{children}</>;
}
