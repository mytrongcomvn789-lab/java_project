import apiClient from './apiClient';
import type { OrderDetail } from './orderService';

export interface AvailabilitySlot {
  slotStart: string | number;
  slotEnd: string | number;
  used: number;
  free: number;
  bookable: boolean;
}

export interface StationAvailability {
  stationId: string;
  stationName: string;
  stationStatus: string;
  capacity: number;
  slotMinutes: number;
  date: string;
  timeZone: string;
  slots: AvailabilitySlot[];
}

export const VN_TIME_ZONE = 'Asia/Ho_Chi_Minh';

// Backend trả Instant dạng chuỗi ISO; nếu là số thì hiểu là giây kể từ 1970.
export function toMillis(value: string | number): number {
  return typeof value === 'number' ? Math.round(value * 1000) : Date.parse(value);
}

export function todayInVietnam(): string {
  return new Date().toLocaleDateString('en-CA', { timeZone: VN_TIME_ZONE });
}

export function formatSlotTime(value: string | number): string {
  return new Date(toMillis(value)).toLocaleTimeString('vi-VN', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
    timeZone: VN_TIME_ZONE,
  });
}

export const scheduleService = {
  // date dạng yyyy-MM-dd theo giờ Việt Nam.
  availability: async (stationId: string, date: string) =>
    (
      await apiClient.get<StationAvailability>(`/v1/stations/${stationId}/availability`, {
        params: { date },
      })
    ).data,
  // Lên lịch (APPROVED) hoặc giao lại (FAILED). Sai phiên bản: 412; slot đầy: 409 SLOT_FULL.
  schedule: async (orderId: string, etag: string, slotStart: string): Promise<OrderDetail> => {
    const response = await apiClient.post<OrderDetail>(
      `/v1/orders/${orderId}/schedules`,
      { slotStart },
      { headers: { 'If-Match': etag } },
    );
    return { ...response.data, etag: response.headers.etag };
  },
};