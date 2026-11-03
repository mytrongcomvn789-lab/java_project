package com.smartdrone.application.users.dto;

import com.smartdrone.domain.enums.RoleName;

// PATCH: null nghĩa là giữ nguyên; phải có ít nhất một trường khác null.
public record UpdateUserAccessRequest(RoleName role, Boolean enabled) { }
