package com.smartdrone.application.statistics;

/** 5.1: mốc ngày dùng để lọc thống kê (xem docs/05-api/statistics-api.md, mục 1). */
public enum DateBasis {
    CREATED,   // ngày tạo đơn (orders.created_at)
    COMPLETED  // ngày hoàn tất: dòng lịch sử của trạng thái cuối (DELIVERED/RETURNED/CANCELLED/REJECTED)
}
