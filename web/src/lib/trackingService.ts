import apiClient from './apiClient';

export interface StationPoint {
  id: string;
  name: string | null;
  latitude: number | null;
  longitude: number | null;
  status: string | null;
}
export interface TimelineEntry {
  eventId: string;
  orderVersion: number;
  fromStatus: string | null;
  toStatus: string;
  toStatusLabel: string;
  action: string | null;
  actorRole: string | null;
  source: string;
  reason: string | null;
  reasonCode?: string | null;
  attemptId: string | null;
  attemptNo: number | null;
  occurredAt: string;
}
export interface AttemptView {
  id: string;
  attemptNo: number;
  status: "ACTIVE" | "RELEASED";
  slotStart: string;
  slotEnd: string;
  createdAt: string;
  releasedAt: string | null;
}
export interface Tracking {
  etag?: string;
  orderId: string;
  version: number;
  status: string;
  statusLabel: string;
  terminal: boolean;
  attempt: {
    id: string;
    attemptNo: number;
    slotStart: string;
    slotEnd: string;
  } | null;
  pickup: StationPoint;
  dropoff: StationPoint;
  map: { mode: string; liveGpsAvailable: boolean; note: string };
  position: {
    phase: 'NOT_STARTED' | 'AT_PICKUP' | 'IN_FLIGHT' | 'AT_DROPOFF' | 'UNKNOWN';
    simulated: boolean;
  };
  timeline: TimelineEntry[];
  allowedActions: string[];
  attempts: AttemptView[];
  maxAttempts: number;
  generatedAt: string;
}

export const trackingService = {
  get: async (id: string): Promise<Tracking> => {
    const r = await apiClient.get<Tracking>(`/v1/orders/${id}/tracking`);
    return { ...r.data, etag: r.headers.etag };
  },
  // DISPATCH (trạm gửi) / ARRIVE (trạm nhận). Server tự lấy người thao tác và thời điểm.
  advance: async (
    id: string,
    action: 'dispatch' | 'arrive',
    etag: string,
    note: string,
  ) => {
    const r = await apiClient.post(
      `/v1/orders/${id}/${action}`,
      note.trim() ? { note: note.trim() } : {},
      { headers: { 'If-Match': etag } },
    );
    return r.data;
  },
};

/**
 * Hợp nhất timeline: khử trùng theo orderVersion, luôn sắp tăng dần, bỏ qua bản cũ hơn.
 * Sự kiện trùng hoặc đến sai thứ tự không làm mất/đảo bước; có khoảng trống phiên bản thì báo cần tải lại REST.
 */
export function mergeTimeline(
  current: TimelineEntry[],
  incoming: TimelineEntry[],
) {
  const byVersion = new Map<number, TimelineEntry>();
  [...current, ...incoming].forEach((e) => byVersion.set(e.orderVersion, e));
  const merged = [...byVersion.values()].sort(
    (a, b) => a.orderVersion - b.orderVersion,
  );
  const hasGap = merged.some(
    (e, i) => i > 0 && e.orderVersion !== merged[i - 1].orderVersion + 1,
  );
  return { merged, hasGap };
}
