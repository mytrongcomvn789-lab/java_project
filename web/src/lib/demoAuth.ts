import type { AuthUser, RoleName } from "../types/api";

const DEMO_NAMES: Record<RoleName, string> = {
  CUSTOMER: "Nguyễn Văn An",
  DISPATCHER: "Trần Thị Bình",
  STATION_OPERATOR: "Lê Công Cường",
  LOGISTICS_MANAGER: "Phạm Minh Dũng",
  SYSTEM_ADMIN: "Quản Trị Viên",
};

// Người dùng giả cho chế độ demo (VITE_DEMO_AUTH=true)
export const buildDemoUser = (role: RoleName): AuthUser => ({
  id: `demo-${role.toLowerCase()}`,
  email: `${role.toLowerCase()}@demo.local`,
  fullName: DEMO_NAMES[role],
  role,
});
