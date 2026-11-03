// Trạng thái đơn giao hàng theo SRS (FR-06) + 2 trạng thái phát sinh trong sơ đồ tuần tự/hoạt động.
export type DeliveryStatus =
  | "PENDING"
  | "APPROVED"
  | "SCHEDULED"
  | "PREPARING"
  | "DELIVERING"
  | "DELIVERED"
  | "FAILED"
  | "REJECTED"
  | "CANCELLED";

export const STATUS_LABELS: Record<DeliveryStatus, string> = {
  PENDING: "Chờ xử lý",
  APPROVED: "Đã phê duyệt",
  SCHEDULED: "Đã lên lịch",
  PREPARING: "Đang chuẩn bị",
  DELIVERING: "Đang giao",
  DELIVERED: "Đã giao",
  FAILED: "Giao thất bại",
  REJECTED: "Bị từ chối",
  CANCELLED: "Đã hủy",
};

// 7 trạng thái chính của FR-06
export const MAIN_STATUSES: DeliveryStatus[] = [
  "PENDING",
  "APPROVED",
  "SCHEDULED",
  "PREPARING",
  "DELIVERING",
  "DELIVERED",
  "FAILED",
];
