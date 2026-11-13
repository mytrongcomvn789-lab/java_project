package com.smartdrone.application.orders.dto;

import java.util.List;

public record OrderPage(List<OrderSummary> items, int page, int pageSize, long total) { }