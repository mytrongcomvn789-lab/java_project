import apiClient from "./apiClient";
import type { PackageView } from "./packageService";
import type { OrderRequestBody } from "./orderForm";

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
  eventId: string;
  orderVersion: number;
  fromStatus: string | null;
  toStatus: string;
  action: string | null;
  actorId: string | null;
  actorRole: string | null;
  source: string;
  reason: string | null;
  reasonCode?: string | null;
  occurredAt: string;
}
export interface OrderDetail {
  etag?: string;
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

export interface OrderCreated {
  id: string;
  version: number;
  status: string;
  statusLabel: string;
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

// If-Match: ưu tiên ETag server trả (đã có dấu nháy kép), không có thì dựng từ version.
export function ifMatchOf(
  order: Pick<OrderDetail, "etag" | "version">,
): string {
  return order.etag ?? `"${order.version}"`;
}

export const orderService = {
  list: async (f: OrderFilters, page: number, pageSize: number) => {
    const params: Record<string, string | number> = { page, pageSize };
    (Object.keys(f) as (keyof OrderFilters)[]).forEach((k) => {
      if (f[k]) params[k] = f[k];
    });
    return (await apiClient.get<OrderPage>("/v1/orders", { params })).data;
  },
  get: async (id: string): Promise<OrderDetail> => {
    const response = await apiClient.get<OrderDetail>(`/v1/orders/${id}`);
    return { ...response.data, etag: response.headers.etag };
  },
  review: async (
    id: string,
    action: "approve" | "reject",
    etag: string,
    reason: string,
  ): Promise<OrderDetail> => {
    const response = await apiClient.post<OrderDetail>(
      `/v1/orders/${id}/${action}`,
      action === "reject" ? { reason } : undefined,
      { headers: { "If-Match": etag } },
    );
    return { ...response.data, etag: response.headers.etag };
  },
  // Khách sửa đơn PENDING của mình (thay toàn bộ nội dung). Sai phiên bản: 412; đơn không còn PENDING: 409.
  update: async (id: string, etag: string, body: OrderRequestBody) =>
    (
      await apiClient.put<OrderCreated>(`/v1/orders/${id}`, body, {
        headers: { "If-Match": etag },
      })
    ).data,
  // Khách hủy đơn PENDING của mình.
  cancel: async (id: string, etag: string) =>
    (
      await apiClient.post<OrderCreated>(`/v1/orders/${id}/cancel`, undefined, {
        headers: { "If-Match": etag },
      })
    ).data,
  // Idempotency-Key bắt buộc: gửi lại cùng key và cùng nội dung thì không tạo đơn thứ hai.
  create: async (body: OrderRequestBody, idempotencyKey: string) =>
    (
      await apiClient.post<OrderCreated>("/v1/orders", body, {
        headers: { "Idempotency-Key": idempotencyKey },
      })
    ).data,
};
