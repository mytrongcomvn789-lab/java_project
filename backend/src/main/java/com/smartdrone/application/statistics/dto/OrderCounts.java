package com.smartdrone.application.statistics.dto;

/** Số đơn theo nhóm; mỗi đơn đúng một nhóm theo trạng thái hiện tại (mục 3 của tài liệu API). */
public record OrderCounts(long total, long pending, long inProgress, long delivered, long failed,
                          long returning, long returned, long cancelled, long rejected) {
    public static final OrderCounts EMPTY = new OrderCounts(0, 0, 0, 0, 0, 0, 0, 0, 0);

    /** Mẫu của tỷ lệ giao: đơn đã có kết quả giao hàng. */
    public long deliveryOutcomes() {
        return delivered + failed + returning + returned;
    }
}
