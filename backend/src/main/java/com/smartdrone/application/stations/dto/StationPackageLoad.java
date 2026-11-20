package com.smartdrone.application.stations.dto;

/**
 * Số KIỆN của một trạm theo vị trí. atStation = kiện thật sự đang nằm ở trạm
 * (đã nhận ở trạm gửi + đã tới trạm nhận). Khác với capacity (đếm số lịch giao theo khung giờ).
 */
public record StationPackageLoad(long scheduled, long preparing, long inbound, long waitingPickup,
                                 long atStation, long processingTotal) { }