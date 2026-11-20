package com.smartdrone.application.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotEmpty;

public final class SettingDtos {
    private SettingDtos() { }

    // type: INT | BOOLEAN | STRING. min/max chỉ có với INT.
    public record SettingView(String key, String group, String label, String description, String type,
                              String value, String defaultValue, Integer min, Integer max,
                              String updatedBy, Instant updatedAt) { }

    public record SettingsResponse(List<SettingView> settings) { }

    public record UpdateSettingsRequest(
            @NotEmpty(message = "Chưa có cấu hình nào để lưu")
            Map<String, String> values) { }
}
