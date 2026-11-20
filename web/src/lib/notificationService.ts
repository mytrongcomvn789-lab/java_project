import apiClient from "./apiClient";

/** Khớp NotificationView ở backend (docs: xem phần hợp đồng API bên dưới). */
export interface NotificationItem {
  id: string;
  eventId: string;
  type: string;
  event: string;
  orderId: string;
  status: string;
  title: string;
  body: string;
  detailPath: string;
  createdAt: string;
  readAt: string | null;
}

export interface NotificationPage {
  items: NotificationItem[];
  page: number;
  pageSize: number;
  total: number;
  unreadCount: number;
}

export interface NotificationRealtimeEvent {
  schemaVersion: number;
  type: string;
  notification: NotificationItem;
}

export const notificationService = {
  async list(
    params: { unreadOnly?: boolean; page?: number; pageSize?: number } = {},
  ): Promise<NotificationPage> {
    const res = await apiClient.get<NotificationPage>("/v1/notifications", {
      params,
    });
    return res.data;
  },
  async markRead(id: string): Promise<void> {
    await apiClient.post(`/v1/notifications/${id}/read`);
  },
  async markAllRead(): Promise<void> {
    await apiClient.post("/v1/notifications/read-all");
  },
};
