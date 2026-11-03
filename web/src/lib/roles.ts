import type { RoleName } from "../types/api";

export const ROLES: RoleName[] = [
  "CUSTOMER",
  "DISPATCHER",
  "STATION_OPERATOR",
  "LOGISTICS_MANAGER",
  "SYSTEM_ADMIN",
];

export const ROLE_LABELS: Record<RoleName, string> = {
  CUSTOMER: "Khách hàng",
  DISPATCHER: "Điều phối viên",
  STATION_OPERATOR: "Nhân viên trạm",
  LOGISTICS_MANAGER: "Quản lý hậu cần",
  SYSTEM_ADMIN: "Quản trị viên",
};

export const ROLE_SHORT_LABELS: Record<RoleName, string> = {
  CUSTOMER: "Khách hàng",
  DISPATCHER: "Điều phối",
  STATION_OPERATOR: "Trạm",
  LOGISTICS_MANAGER: "Hậu cần",
  SYSTEM_ADMIN: "Admin",
};

// Mô tả ngắn theo SRS / use case của từng tác nhân
export const ROLE_DESCRIPTIONS: Record<RoleName, string> = {
  CUSTOMER: "Tạo yêu cầu, theo dõi và xác nhận nhận hàng",
  DISPATCHER: "Phê duyệt, lên lịch và xử lý giao hàng thất bại",
  STATION_OPERATOR: "Kiểm tra và tiếp nhận kiện hàng tại trạm",
  LOGISTICS_MANAGER: "Dashboard, báo cáo và hiệu suất vận hành",
  SYSTEM_ADMIN: "Tài khoản, vai trò, cấu hình và audit log",
};
