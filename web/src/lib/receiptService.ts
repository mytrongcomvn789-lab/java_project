import apiClient from './apiClient';
import type { OrderDetail, OrderFilters, OrderPage } from './orderService';

export interface ReceiptContext {
  order: OrderDetail;
  senderName: string | null;
  packageId: string | null;
  attempt: { id: string; attemptNo: number; slotStart: string; slotEnd: string } | null;
}
export const receiptService = {
  list: async (filters: OrderFilters, page: number, pageSize: number) => {
    const params = { ...filters, page, pageSize };
    return (await apiClient.get<OrderPage>('/v1/station-receipts', { params })).data;
  },
  context: async (id: string): Promise<ReceiptContext> => {
    const r = await apiClient.get<ReceiptContext>(`/v1/orders/${id}/receipt-context`);
    return { ...r.data, order: { ...r.data.order, etag: r.headers.etag } };
  },
  receive: async (context: ReceiptContext, note: string): Promise<OrderDetail> => {
    const r = await apiClient.post<OrderDetail>(`/v1/orders/${context.order.id}/receipts`,
      { attemptId: context.attempt?.id, packageId: context.packageId, note },
      { headers: { 'If-Match': context.order.etag ?? `"${context.order.version}"` } });
    return { ...r.data, etag: r.headers.etag };
  },
};
