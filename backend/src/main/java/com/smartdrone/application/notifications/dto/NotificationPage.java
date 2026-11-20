package com.smartdrone.application.notifications.dto;

import java.util.List;

public record NotificationPage(List<NotificationView> items, int page, int pageSize, long total, long unreadCount) { }