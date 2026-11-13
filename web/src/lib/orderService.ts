import apiClient from "./apiClient";
import type { PackageView } from "./packageService";

export interface OrderSummary {
  id: string;
  status: string;
  statusLabel: string;
  pickupStationId: string;
  dropoffStationId: string;
  recipientName: string;
  recipientAddressLine: string;
  packageDescription: string | null;
  packageWeightGrams: number | null;
  createdAt: string;
  updatedAt: string;
}
export interface OrderPage {
  items: OrderSummary[];
  page: number;
  pageSize: number;
  total: number;
}
export interface StatusHistory {
  orderVersion: number;
  fromStatus: string | null;
  toStatus: string;
  action: string | null;
  actorRole: string | null;
  source: string;
  reason: string | null;
  occurredAt: string;
}
export interface OrderDetail {
  id: string;
  version: number;
  status: string;
  statusLabel: string;
  pickupStationId: string;
  dropoffStationId: string;
  recipient: {
    name: string;
    phone: string;
    addressLine: string;
    latitude: number;
    longitude: number;
  };
  package: PackageView | null;
  history: StatusHistory[];
  createdAt: string;
  updatedAt: string;
}
export interface OrderFilters {
  code: string;
  status: string;
  from: string;
  to: string;
  stationId: string;
  sort: string;
}

// Trạng thái theo backend (OrderStatus), khác danh sách cũ trong deliveryStatus.ts.
export const ORDER_STATUS_OPTIONS: [string, string][] = [
  ["PENDING", "Chờ duyệt"],
  ["APPROVED", "Đã duyệt"],
  ["REJECTED", "Bị từ chối"],
  ["CANCELLED", "Đã hủy"],
  ["SCHEDULED", "Đã lên lịch"],
  ["PREPARING", "Đang chuẩn bị"],
  ["IN_TRANSIT", "Đang giao"],
  ["AWAITING_PICKUP", "Chờ nhận hàng"],
  ["DELIVERED", "Đã giao"],
  ["FAILED", "Giao thất bại"],
  ["RETURNING", "Đang hoàn hàng"],
  ["RETURNED", "Đã hoàn hàng"],
];

export const orderService = {
  list: async (f: OrderFilters, page: number, pageSize: number) => {
    const params: Record<string, string | number> = { page, pageSize };
    (Object.keys(f) as (keyof OrderFilters)[]).forEach((k) => {
      if (f[k]) params[k] = f[k];
    });
    return (await apiClient.get<OrderPage>("/v1/orders", { params })).data;
  },
  get: async (id: string) =>
    (await apiClient.get<OrderDetail>(`/v1/orders/${id}`)).data,
};
