import { ApiRequestError } from "./apiClient";

export interface ConfirmErrorInfo {
  message: string;
  mustReload: boolean; // true: dữ liệu trên màn hình có thể đã cũ, phải tải lại đơn trước khi làm tiếp
  clearCode: boolean; // true: xóa mã đã nhập để nhân viên nhập lại
}

// Giải thích lỗi khi nhân viên xác nhận giao hàng (POST /orders/{id}/confirmations).
// Ưu tiên theo "code" của backend; chỉ dùng status khi không có code.
export function explainConfirmError(e: unknown): ConfirmErrorInfo {
  const err = e instanceof ApiRequestError ? e : undefined;
  const status = err?.status;
  const code = err?.code;

  switch (code) {
    case "PICKUP_CODE_INVALID":
      return {
        message:
          err?.message || "Mã nhận hàng không đúng. Hãy nhập lại mã khách đưa.",
        mustReload: false,
        clearCode: true,
      };
    case "PICKUP_CODE_EXPIRED":
      return {
        message: "Mã đã hết hạn. Nhờ khách mở lại mã trên ứng dụng rồi nhập mã mới.",
        mustReload: false,
        clearCode: true,
      };
    case "PICKUP_CODE_LOCKED":
      return {
        message:
          "Mã đã bị khóa do nhập sai quá nhiều lần. Nhờ khách mở lại mã để lấy mã mới.",
        mustReload: false,
        clearCode: true,
      };
    case "PICKUP_CODE_USED":
      return {
        message: "Mã này đã được dùng. Hãy tải lại đơn để xem trạng thái mới.",
        mustReload: true,
        clearCode: true,
      };
    case "VERSION_CONFLICT":
      return {
        message: "Đơn vừa được cập nhật ở nơi khác. Hãy tải lại đơn rồi thử lại.",
        mustReload: true,
        clearCode: false,
      };
    case "PRECONDITION_REQUIRED":
      return {
        message: "Thiếu phiên bản đơn. Hãy tải lại đơn rồi thử lại.",
        mustReload: true,
        clearCode: false,
      };
    case "INVALID_STATE_TRANSITION":
      return {
        message: "Đơn không còn ở trạng thái chờ nhận hàng. Hãy tải lại đơn.",
        mustReload: true,
        clearCode: true,
      };
    case "STORAGE_UNAVAILABLE":
      return {
        message: "Kho lưu ảnh chưa sẵn sàng nên chưa xác nhận được. Hãy thử lại sau.",
        mustReload: false,
        clearCode: false,
      };
    case "FILE_NOT_UPLOADED":
    case "FILE_INVALID_STATE":
      return {
        message: "Ảnh xác nhận chưa dùng được. Hãy chọn và tải lại ảnh khác.",
        mustReload: false,
        clearCode: false,
      };
    case "VALIDATION_ERROR":
      return {
        message: err?.message || "Dữ liệu chưa hợp lệ. Kiểm tra mã và ảnh.",
        mustReload: false,
        clearCode: false,
      };
  }

  if (status === 412)
    return {
      message: "Đơn vừa được cập nhật ở nơi khác. Hãy tải lại đơn rồi thử lại.",
      mustReload: true,
      clearCode: false,
    };
  if (status === 403 || status === 404)
    return {
      message: "Không tìm thấy đơn hoặc bạn không còn quyền thao tác tại trạm này. Hãy tải lại.",
      mustReload: true,
      clearCode: false,
    };
  // Mất mạng hoặc lỗi 5xx: không biết server đã ghi nhận hay chưa → phải kiểm tra lại trước khi thử tiếp.
  if (!status || status >= 500)
    return {
      message: "Chưa xác nhận được kết quả. Hãy tải lại đơn để kiểm tra trước khi thử tiếp.",
      mustReload: true,
      clearCode: false,
    };
  return {
    message: e instanceof Error ? e.message : "Không thể xác nhận giao hàng.",
    mustReload: false,
    clearCode: false,
  };
}