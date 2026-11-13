package com.smartdrone.application.orders.dto;

/** Kết quả tạo đơn. replayed = true nghĩa là đây là đơn đã tạo từ lần gửi trước (không tạo mới). */
public record OrderCreation(OrderView order, boolean replayed) { }