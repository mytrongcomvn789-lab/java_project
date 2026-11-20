package com.smartdrone.application.admin;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.application.admin.dto.SettingDtos.SettingView;
import com.smartdrone.application.admin.dto.SettingDtos.SettingsResponse;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.domain.entity.SystemSetting;
import com.smartdrone.infrastructure.persistence.SystemSettingRepository;
import com.smartdrone.infrastructure.persistence.UserRepository;

// FR-02: cấu hình hệ thống (chỉ SYSTEM_ADMIN). Validate kiểu và khoảng giá trị theo SettingDefinitions.
@Service
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminSettingsService {
    private final SystemSettingRepository settings;
    private final UserRepository users;
    private final AuditService audit;
    private final Clock clock;

    public AdminSettingsService(SystemSettingRepository settings, UserRepository users, AuditService audit,
            Clock clock) {
        this.settings = settings;
        this.users = users;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SettingsResponse list() {
        Map<String, SystemSetting> stored = new LinkedHashMap<>();
        settings.findAll().forEach(s -> stored.put(s.getKey(), s));
        List<SettingView> views = new ArrayList<>();
        for (SettingDefinitions.Def d : SettingDefinitions.ALL) {
            SystemSetting s = stored.get(d.key());
            views.add(new SettingView(d.key(), d.group(), d.label(), d.description(), d.type(),
                    s == null ? d.defaultValue() : s.getValue(), d.defaultValue(), d.min(), d.max(),
                    s == null ? null : s.getUpdatedBy(), s == null ? null : s.getUpdatedAt()));
        }
        return new SettingsResponse(views);
    }

    @Transactional
    public SettingsResponse update(UUID actorId, Map<String, String> values) {
        // Validate toàn bộ trước, chỉ lưu khi tất cả hợp lệ.
        Map<String, String> normalized = new LinkedHashMap<>();
        for (var e : values.entrySet()) {
            SettingDefinitions.Def def = SettingDefinitions.BY_KEY.get(e.getKey());
            if (def == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Cấu hình không tồn tại: " + e.getKey());
            }
            normalized.put(def.key(), def.normalize(e.getValue()));
        }
        String actor = users.findById(actorId).map(u -> u.getEmail()).orElse(null);
        List<String> changes = new ArrayList<>();
        for (var e : normalized.entrySet()) {
            SettingDefinitions.Def def = SettingDefinitions.BY_KEY.get(e.getKey());
            SystemSetting s = settings.findById(e.getKey()).orElse(null);
            String old = s == null ? def.defaultValue() : s.getValue();
            if (s == null) {
                s = new SystemSetting(def.key(), e.getValue(), def.type(), def.description(), actor, clock.instant());
                settings.save(s);
            } else if (!old.equals(e.getValue())) {
                s.change(e.getValue(), actor, clock.instant());
            } else {
                continue;
            }
            changes.add(def.key() + ": " + old + " -> " + e.getValue());
        }
        if (!changes.isEmpty()) {
            audit.record(actorId, "SETTINGS_UPDATED", "SETTINGS", null, String.join("; ", changes));
        }
        settings.flush();
        return list();
    }
}
