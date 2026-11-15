import { ApiRequestError } from './apiClient';

export interface OrderErrorInfo {
  message: string;
  mustReload: boolean; // true: dữ liệu trên màn hình có thể đã cũ, phải tải lại đơn trước khi làm tiếp
}

// Giải thích lỗi khi SỬA hoặc HỦY đơn (các thao tác gửi If-Match).
// 412 = phiên bản cũ, 409 = đơn không còn ở trạng thái cho phép, 404/403 = ngoài phạm vi.
export function explainOrderError(e: unknown): OrderErrorInfo {
  const status = e instanceof ApiRequestError ? e.status : undefined;
  const code = e instanceof ApiRequestError ? e.code : undefined;
  if (status === 412 || code === 'VERSION_CONFLICT')
    return {
      message: 'Đơn vừa được cập nhật ở nơi khác. Hãy tải lại để xem dữ liệu mới nhất.',
      mustReload: true,
    };
  if (status === 428)
    return { message: 'Thiếu phiên bản đơn. Hãy tải lại đơn rồi thử lại.', mustReload: true };
  if (status === 409)
    return {
      message: 'Đơn không còn ở trạng thái chờ duyệt nên không thể thay đổi. Hãy tải lại để xem trạng thái mới.',
      mustReload: true,
    };
  if (status === 403 || status === 404)
    return {
      message: 'Không tìm thấy đơn hoặc bạn không còn quyền thao tác. Hãy tải lại.',
      mustReload: true,
    };
  if (!status || status >= 500)
    return {
      message: 'Chưa xác nhận được kết quả. Hãy tải lại đơn để kiểm tra trước khi thử tiếp.',
      mustReload: true,
    };
  return { message: e instanceof Error ? e.message : 'Không thể thực hiện thao tác.', mustReload: false };
}