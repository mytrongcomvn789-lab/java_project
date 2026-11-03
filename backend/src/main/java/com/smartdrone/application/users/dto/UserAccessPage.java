package com.smartdrone.application.users.dto;

import java.util.List;

public record UserAccessPage(List<UserAccessView> items, int page, int pageSize, long total) { }
