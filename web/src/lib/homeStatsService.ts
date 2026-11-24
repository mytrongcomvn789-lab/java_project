import { orderService, type OrderFilters } from "./orderService";
import { reportService } from "./reportService";
import type { RoleName } from "../types/api";

export interface HomeStats {
  total: number;
  inProgress: number;
  delivered: number;
  failed: number;
}

// "Đang xử lý" dùng đúng định nghĩa của dashboard (docs/05-api/statistics-api.md, mục inProgress).
const IN_PROGRESS = ["APPROVED", "SCHEDULED", "PREPARING", "IN_TRANSIT", "AWAITING_PICKUP"];

const NO_FILTER: OrderFilters = { code: "", status: "", from: "", to: "", stationId: "", sort: "" };

// Vai trò có số liệu đơn trên trang chủ. Quản trị viên không xem đơn nên không có.
export const HOME_STAT_ROLES: RoleName[] = ["CUSTOMER", "DISPATCHER", "STATION_OPERATOR", "LOGISTICS_MANAGER"];

// Backend tự lọc theo phạm vi của người đăng nhập, nên chỉ cần đếm tổng của từng trạng thái.
const countOrders = async (status: string) =>
  (await orderService.list({ ...NO_FILTER, status }, 1, 1)).total;

export const homeStatsService = {
  load: async (role: RoleName): Promise<HomeStats> => {
    if (role === "LOGISTICS_MANAGER") {
      // Cùng nguồn với Dashboard (30 ngày gần nhất) để hai trang không lệch số.
      const { orders } = await reportService.summary({});
      return {
        total: orders.total,
        inProgress: orders.inProgress,
        delivered: orders.delivered,
        failed: orders.failed,
      };
    }
    const [total, delivered, failed, ...progress] = await Promise.all([
      countOrders(""),
      countOrders("DELIVERED"),
      countOrders("FAILED"),
      ...IN_PROGRESS.map(countOrders),
    ]);
    return { total, delivered, failed, inProgress: progress.reduce((sum, n) => sum + n, 0) };
  },
};
