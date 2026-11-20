package com.smartdrone.application.integration;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.integration.dto.IntegrationOrderStatus;
import com.smartdrone.application.integration.dto.IntegrationStationView;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.infrastructure.persistence.OrderRepository;
import com.smartdrone.infrastructure.persistence.StationRepository;

/**
 * Đọc THẲNG từ repository, không gọi qua OrderQueryService/StationService hiện có — hai service đó có
 * @PreAuthorize gắn với vai trò người dùng JWT, còn API key không mang vai trò nào. Gọi qua sẽ luôn bị
 * AccessDeniedException (403) dù scope đã đúng — đây là bug CI đã bắt được và được sửa tại đây.
 */
@Service
public class IntegrationQueryService {
    private final OrderRepository orders;
    private final StationRepository stations;

    public IntegrationQueryService(OrderRepository orders, StationRepository stations) {
        this.orders = orders;
        this.stations = stations;
    }

    @Transactional(readOnly = true)
    public IntegrationOrderStatus orderStatus(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        return new IntegrationOrderStatus(order.getId(), order.getStatus(), order.getStatus().label(),
                order.getStatus().isTerminal(), order.getUpdatedAt());
    }

    @Transactional(readOnly = true)
    public List<IntegrationStationView> stations() {
        return stations.findAll(Sort.by(Sort.Order.asc("name"), Sort.Order.asc("id"))).stream()
                .map(s -> new IntegrationStationView(
                        s.getId(), s.getName(), s.getLatitude(), s.getLongitude(), s.getStatus()))
        return stations.findAll().stream()
                .map(s -> new IntegrationStationView(s.getId(), s.getName(), s.getLatitude(), s.getLongitude(),
                        s.getStatus()))
                .toList();
    }
}