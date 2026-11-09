import type { RoleName } from "../types/api";

export interface NavItem {
  to: string;
  label: string;
  icon: string;
  end?: boolean;
  roles: RoleName[] | "ALL";
  code?: string; // mã yêu cầu trong SRS (FR-xx / NFR-xx)
  summary?: string; // mô tả ngắn của trang
  features?: string[]; // các use case của trang (theo sơ đồ use case)
}

// Menu theo vai trò, bám sát SRS (FR-01 → FR-10) và sơ đồ use case.
// Các trang ngoài "/" và "/profile" hiện là trang tạm, sẽ được làm ở các task sau.
export const NAV_ITEMS: NavItem[] = [
  { to: "/", label: "Trang chủ", icon: "▦", end: true, roles: "ALL" },

  // ===== Khách hàng =====
  {
    to: "/deliveries/new",
    label: "Tạo yêu cầu giao hàng",
    icon: "✚",
    roles: ["CUSTOMER"],
    code: "FR-04",
    summary: "Gửi yêu cầu giao hàng mới bằng drone.",
    features: [
      "Nhập thông tin kiện hàng (trọng lượng, kích thước, mô tả)",
      "Chọn địa chỉ giao hàng",
      "Hệ thống ước tính thời gian giao (ETA)",
      "Gửi yêu cầu, trạng thái ban đầu là Chờ xử lý",
    ],
  },
  {
    to: "/deliveries",
    label: "Đơn giao hàng của tôi",
    icon: "☷",
    roles: ["CUSTOMER"],
    code: "FR-04",
    summary: "Xem, theo dõi và quản lý các đơn giao hàng của bạn.",
    features: [
      "Xem danh sách đơn và trạng thái",
      "Cập nhật hoặc hủy yêu cầu khi đủ điều kiện",
      "Theo dõi tiến trình giao hàng (vị trí, ETA)",
      "Xác nhận đã nhận hàng",
    ],
  },
  {
    to: "/addresses",
    label: "Địa chỉ giao hàng",
    icon: "⌖",
    roles: ["CUSTOMER"],
    code: "FR-03",
    summary: "Quản lý các địa chỉ giao hàng của bạn.",
    features: [
      "Thêm, sửa, xóa địa chỉ giao hàng",
      "Chọn địa chỉ khi tạo yêu cầu",
    ],
  },
  {
    to: "/assistant",
    label: "Trợ lý AI",
    icon: "✦",
    roles: ["CUSTOMER"],
    code: "FR-10",
    summary: "Được AI hỗ trợ về đơn hàng và quá trình giao hàng.",
    features: ["Chat với AI Assistant", "Tóm tắt thông tin giao hàng"],
  },

  // ===== Điều phối viên =====
  {
    to: "/dispatch/requests",
    label: "Yêu cầu chờ duyệt",
    icon: "✓",
    roles: ["DISPATCHER"],
    code: "FR-04",
    summary: "Kiểm tra và quyết định các yêu cầu giao hàng mới.",
    features: [
      "Xem các yêu cầu ở trạng thái Chờ xử lý",
      "Kiểm tra yêu cầu",
      "Phê duyệt hoặc từ chối yêu cầu",
    ],
  },
  {
    to: "/dispatch/schedule",
    label: "Lịch giao hàng",
    icon: "◷",
    roles: ["DISPATCHER"],
    code: "FR-04",
    summary: "Lên lịch giao cho các yêu cầu đã phê duyệt.",
    features: ["Lên lịch giao hàng", "Thông báo lịch giao cho khách hàng"],
  },
  {
    to: "/dispatch/failures",
    label: "Giao hàng thất bại",
    icon: "⚠",
    roles: ["DISPATCHER"],
    code: "FR-06",
    summary: "Theo dõi và xử lý các đơn giao thất bại.",
    features: [
      "Xem danh sách đơn giao thất bại",
      "Giao lại (lên lịch lại) hoặc hủy / hoàn đơn",
    ],
  },
  {
    to: "/deliveries",
    label: "Theo dõi giao hàng",
    icon: "☷",
    roles: ["DISPATCHER"],
    code: "FR-08",
    summary: "Theo dõi trạng thái của tất cả đơn giao hàng.",
    features: [
      "Xem danh sách và trạng thái đơn",
      "Theo dõi tiến trình giao hàng",
    ],
  },
  {
    to: "/customers",
    label: "Khách hàng",
    icon: "☺",
    roles: ["DISPATCHER"],
    code: "FR-03",
    summary: "Quản lý thông tin khách hàng.",
    features: ["Xem và cập nhật thông tin khách hàng"],
  },

  // ===== Nhân viên trạm =====
  {
    to: "/station",
    label: "Thông tin trạm",
    icon: "⌂",
    roles: ["STATION_OPERATOR"],
    code: "FR-07",
    summary: "Quản lý thông tin trạm hạ cánh.",
    features: ["Xem và cập nhật thông tin trạm"],
  },
  {
    to: "/station/availability",
    label: "Tình trạng sẵn sàng",
    icon: "◉",
    roles: ["STATION_OPERATOR"],
    code: "FR-07",
    summary: "Theo dõi trạng thái và khả năng sẵn sàng của trạm.",
    features: ["Theo dõi tình trạng sẵn sàng của trạm"],
  },
  {
    to: "/station/parcels",
    label: "Tiếp nhận kiện hàng",
    icon: "▣",
    roles: ["STATION_OPERATOR"],
    code: "FR-07",
    summary: "Kiểm tra và tiếp nhận kiện hàng tại trạm.",
    features: [
      "Kiểm tra kiện hàng tại trạm",
      "Xác nhận tiếp nhận / lấy kiện, đơn chuyển sang Đang chuẩn bị",
    ],
  },
  {
    to: "/deliveries",
    label: "Theo dõi giao hàng",
    icon: "☷",
    roles: ["STATION_OPERATOR"],
    code: "FR-08",
    summary: "Theo dõi và cập nhật trạng thái giao hàng liên quan đến trạm.",
    features: [
      "Theo dõi trạng thái giao hàng",
      "Cập nhật trạng thái giao hàng",
    ],
  },

  // ===== Quản lý hậu cần =====
  {
    to: "/reports",
    label: "Thống kê & báo cáo",
    icon: "▤",
    roles: ["LOGISTICS_MANAGER"],
    code: "FR-09",
    summary: "Báo cáo và thống kê hoạt động giao hàng.",
    features: [
      "Thống kê số lượng đơn hàng",
      "Số đơn đang xử lý, đã giao và giao thất bại",
      "Báo cáo hoạt động giao hàng",
    ],
  },
  {
    to: "/performance",
    label: "Hiệu suất hoạt động",
    icon: "↗",
    roles: ["LOGISTICS_MANAGER"],
    code: "FR-09",
    summary: "Theo dõi hiệu suất hoạt động của các trạm.",
    features: ["Theo dõi hiệu suất hoạt động của các trạm"],
  },
  {
    to: "/ai-analytics",
    label: "Phân tích vận hành (AI)",
    icon: "✦",
    roles: ["LOGISTICS_MANAGER"],
    code: "FR-10",
    summary: "Phân tích dữ liệu vận hành với sự hỗ trợ của AI.",
    features: ["Phân tích dữ liệu vận hành"],
  },
  {
    to: "/deliveries",
    label: "Theo dõi giao hàng",
    icon: "☷",
    roles: ["LOGISTICS_MANAGER"],
    code: "FR-08",
    summary: "Theo dõi trạng thái giao hàng trên toàn hệ thống.",
    features: ["Theo dõi trạng thái giao hàng"],
  },

  // ===== Quản trị viên =====
  {
    to: "/admin/users",
    label: "Quản lý tài khoản",
    icon: "☺",
    roles: ["SYSTEM_ADMIN"],
    code: "FR-02",
    summary: "Quản lý tài khoản người dùng trong hệ thống.",
    features: ["Xem, tạo, cập nhật tài khoản", "Khóa / mở khóa tài khoản"],
  },
  {
    to: "/admin/roles",
    label: "Vai trò & quyền",
    icon: "⚿",
    roles: ["SYSTEM_ADMIN"],
    code: "FR-02",
    summary: "Quản lý vai trò và quyền truy cập.",
    features: ["Quản lý vai trò", "Quản lý quyền truy cập theo vai trò"],
  },
  {
    to: "/admin/settings",
    label: "Cấu hình hệ thống",
    icon: "⚙",
    roles: ["SYSTEM_ADMIN"],
    code: "FR-02",
    summary: "Cấu hình các thông số của hệ thống.",
    features: ["Quản lý cấu hình hệ thống"],
  },
  {
    to: "/admin/audit-log",
    label: "Audit Log",
    icon: "☰",
    roles: ["SYSTEM_ADMIN"],
    code: "NFR-07",
    summary: "Truy vết các thao tác quan trọng của người dùng và hệ thống.",
    features: ["Xem nhật ký hoạt động (Audit Log)"],
  },

  // ===== Chung cho mọi vai trò =====
  {
    to: "/profile",
    label: "Hồ sơ",
    icon: "◔",
    roles: "ALL",
    code: "FR-02",
    summary: "Xem và cập nhật thông tin cá nhân.",
  },
];

const allowed = (item: NavItem, role: RoleName) =>
  item.roles === "ALL" || item.roles.includes(role);

export const getMenuForRole = (role: RoleName): NavItem[] =>
  NAV_ITEMS.filter((i) => allowed(i, role));

// Các đường dẫn tạm (chưa có trang thật) - AppRouter dùng để tạo route.
export const PLACEHOLDER_PATHS: string[] = Array.from(
  new Set(
    NAV_ITEMS.map((i) => i.to).filter((p) => !["/", "/profile", "/addresses"].includes(p)),
  ),
);

// Cùng một đường dẫn có thể có nhãn khác nhau theo vai trò (vd /deliveries).
export const findNavItem = (
  path: string,
  role?: RoleName,
): NavItem | undefined =>
  (role
    ? NAV_ITEMS.find((i) => i.to === path && allowed(i, role))
    : undefined) ?? NAV_ITEMS.find((i) => i.to === path);
