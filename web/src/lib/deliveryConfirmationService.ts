import apiClient, { ApiRequestError } from "./apiClient";
import { ifMatchOf, type OrderDetail } from "./orderService";

// GET /v1/orders/{id}/pickup-code (chỉ khách chủ đơn, đơn ở AWAITING_PICKUP)
export interface PickupCode {
  challengeId: string;
  orderId: string;
  otp: string;
  qrPayload: string; // nội dung để vẽ mã QR; nhân viên gửi lại đúng chuỗi này ở trường qrToken
  expiresAt: string;
}

// GET /v1/orders/{id}/confirmation
export interface DeliveryConfirmation {
  id: string;
  orderId: string;
  orderVersion: number;
  method: "OTP" | "QR";
  confirmedBy: string;
  stationId: string;
  photoFileId: string;
  note: string | null;
  confirmedAt: string;
}

// Đúng MỘT trong hai: backend trả 400 nếu gửi cả hai hoặc không gửi cái nào.
export type PickupCredential = { otp: string } | { qrToken: string };

export const deliveryConfirmationService = {
  pickupCode: async (orderId: string): Promise<PickupCode> =>
    (await apiClient.get<PickupCode>(`/v1/orders/${orderId}/pickup-code`)).data,

  // CONFIRM: AWAITING_PICKUP → DELIVERED. Bắt buộc If-Match; người xác nhận và thời điểm do server tự lấy.
  confirm: async (
    order: Pick<OrderDetail, "id" | "etag" | "version">,
    credential: PickupCredential,
    photoFileId: string,
    note: string,
  ): Promise<DeliveryConfirmation> => {
    const body: Record<string, string> = { ...credential, photoFileId };
    if (note.trim()) body.note = note.trim();
    const r = await apiClient.post<DeliveryConfirmation>(
      `/v1/orders/${order.id}/confirmations`,
      body,
      { headers: { "If-Match": ifMatchOf(order) } },
    );
    return r.data;
  },

  // Đơn chưa có bằng chứng thì backend trả 404; ở đây coi là "chưa có" (null).
  get: async (orderId: string): Promise<DeliveryConfirmation | null> => {
    try {
      return (
        await apiClient.get<DeliveryConfirmation>(`/v1/orders/${orderId}/confirmation`)
      ).data;
    } catch (e) {
      if (e instanceof ApiRequestError && e.status === 404) return null;
      throw e;
    }
  },
};