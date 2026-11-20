package com.smartdrone.application.integration;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.integration.dto.IntegrationOrderStatus;
import com.smartdrone.application.integration.dto.IntegrationStationView;
import com.smartdrone.application.stations.StationService;
import com.smartdrone.domain.entity.Order;
import com.smartdrone.infrastructure.persistence.OrderRepository;

@Service
public class IntegrationQueryService {
    private final OrderRepository orders;
    private final StationService stations;

    public IntegrationQueryService(OrderRepository orders, StationService stations) {
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
        return stations.list().stream()
                .map(s -> new IntegrationStationView(s.id(), s.name(), s.latitude(), s.longitude(), s.status()))
                .toList();
    }
}