package com.smartdrone.application.orders;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.smartdrone.application.orders.dto.OrderCreation;
import com.smartdrone.application.orders.dto.OrderRequest;

/** KHÔNG đặt @Transactional ở đây: cần bắt lỗi sau khi transaction của OrderService đã rollback. */
@Service
public class OrderCreationFacade {

    private final OrderService orderService;

    public OrderCreationFacade(OrderService orderService) {
        this.orderService = orderService;
    }

    public OrderCreation create(UUID customerId, String idempotencyKey, OrderRequest request) {
        String key = IdempotencyKeys.require(idempotencyKey);
        try {
            return orderService.createIdempotent(customerId, key, request);
        } catch (DataIntegrityViolationException e) {
            // Hai request cùng key chạy song song: bên thua rơi vào đây. Đọc kết quả bên thắng đã commit.
            // Không có bản ghi nào nghĩa là lỗi này không phải do trùng key, nên ném lại lỗi gốc.
            return orderService.replay(customerId, key, request).orElseThrow(() -> e);
        }
    }
}