import apiClient from "./apiClient";

export type DateBasis = "CREATED" | "COMPLETED";

export interface OrderCounts {
  total: number; pending: number; inProgress: number; delivered: number;
  failed: number; returning: number; returned: number; cancelled: number; rejected: number;
}

export interface OrderStatsSummary {
  from: string; to: string; timezone: string; dateBasis: DateBasis; stationId: string | null;
  orders: OrderCounts;
  rates: { successRate: number | null; returnRate: number | null; cancelRate: number | null; deliverySample: number };
  attempts: { totalAttempts: number; retryAttempts: number; ordersWithRetry: number; ordersWithAttempt: number; retryRate: number | null };
  deliveryTime: { count: number; excludedOrders: number; avgSeconds: number | null; minSeconds: number | null; maxSeconds: number | null };
}

export interface DailyOrderStats { date: string; orders: OrderCounts }

export interface ReportFilters { from?: string; to?: string; dateBasis?: DateBasis; stationId?: string }

export const reportService = {
  summary: async (filters: ReportFilters) =>
    (await apiClient.get<OrderStatsSummary>("/v1/statistics/orders/summary", { params: filters })).data,
  daily: async (filters: ReportFilters) =>
    (await apiClient.get<DailyOrderStats[]>("/v1/statistics/orders/daily", { params: filters })).data,
  exportFile: async (format: "xlsx" | "pdf", filters: ReportFilters) => {
    const response = await apiClient.get(`/v1/statistics/orders/export.${format}`, {
      params: filters,
      responseType: "blob",
    });
    const disposition = response.headers["content-disposition"] as string | undefined;
    const match = disposition?.match(/filename="([^"]+)"/i);
    const filename = match?.[1] ?? `bao-cao-thong-ke.${format}`;
    const url = URL.createObjectURL(response.data as Blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  },
};
