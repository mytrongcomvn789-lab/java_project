package com.smartdrone.domain.enums;

/** Mỗi hành động tương ứng một endpoint riêng (không có PATCH status tùy ý). */
public enum OrderAction {
    APPROVE,        // duyệt đơn
    REJECT,         // từ chối đơn
    CANCEL,         // khách hủy đơn
    SCHEDULE,       // lên lịch (cũng dùng cho retry lần thử mới)
    RECEIVE,        // trạm nhận kiện hàng
    DISPATCH,       // xuất phát
    ARRIVE,         // drone đến trạm nhận
    CONFIRM,        // xác nhận đã giao
    FAIL,           // báo thất bại
    START_RETURN,   // bắt đầu hoàn hàng
    COMPLETE_RETURN // hoàn hàng xong
}