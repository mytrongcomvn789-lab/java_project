package com.smartdrone.application.apikeys.dto;

import java.util.List;

public record ApiKeyPage(List<ApiKeyView> items, int page, int pageSize, long total) { }