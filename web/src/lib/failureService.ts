import type { AxiosResponse } from 'axios';
import apiClient from './apiClient';
import type { OrderDetail } from './orderService';

// 4.8: mã lý do thất bại (khớp enum FailureReason ở backend).
export const FAILURE_REASONS: { code: string; label: string }[] = [
  { code: 'RECIPIENT_ABSENT', label: 'Người nhận không có mặt / không nhận hàng' },
  { code: 'STATION_UNAVAILABLE', label: 'Trạm không sẵn sàng' },
  { code: 'WEATHER', label: 'Thời tiết xấu' },
  { code: 'DRONE_FAULT', label: 'Sự cố drone' },
  { code: 'OTHER', label: 'Lý do khác (bắt buộc ghi chú)' },
];

export const failureReasonLabel = (code?: string | null): string =>
  FAILURE_REASONS.find((r) => r.code === code)?.label ?? '';

const withEtag = async (p: Promise<AxiosResponse<OrderDetail>>): Promise<OrderDetail> => {
  const r = await p;
  return { ...r.data, etag: r.headers.etag };
};

export const failureService = {
  // DISPATCHER: SCHEDULED/PREPARING/IN_TRANSIT/AWAITING_PICKUP → FAILED. Sai phiên bản: 412.
  fail: (orderId: string, etag: string, reasonCode: string, note: string) =>
    withEtag(
      apiClient.post<OrderDetail>(
        `/v1/orders/${orderId}/fail`,
        note.trim() ? { reasonCode, note: note.trim() } : { reasonCode },
        { headers: { 'If-Match': etag } },
      ),
    ),
  // DISPATCHER: FAILED → RETURNING.
  startReturn: (orderId: string, etag: string, reason: string) =>
    withEtag(
      apiClient.post<OrderDetail>(
        `/v1/orders/${orderId}/return`,
        { reason: reason.trim() },
        { headers: { 'If-Match': etag } },
      ),
    ),
  // STATION_OPERATOR của trạm gửi: RETURNING → RETURNED.
  completeReturn: (orderId: string, etag: string, note: string) =>
    withEtag(
      apiClient.post<OrderDetail>(
        `/v1/orders/${orderId}/complete-return`,
        note.trim() ? { note: note.trim() } : {},
        { headers: { 'If-Match': etag } },
      ),
    ),
};
