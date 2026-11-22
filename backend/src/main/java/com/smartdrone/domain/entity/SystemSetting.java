package com.smartdrone.domain.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Cấu hình hệ thống dạng key/value. Định nghĩa (nhóm, kiểu, khoảng giá trị) nằm ở SettingDefinitions.
// Tên cột không dùng "key"/"value" vì là từ khóa SQL.
@Entity
@Table(name = "system_settings")
public class SystemSetting {

    @Id
    @Column(name = "setting_key", length = 100)
    private String key;

    @Column(name = "setting_value", nullable = false, length = 255)
    private String value;

    @Column(name = "value_type", nullable = false, length = 20)
    private String type;

    @Column(length = 500)
    private String description;

    @Column(name = "updated_by", length = 150)
    private String updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SystemSetting() { }

    public SystemSetting(String key, String value, String type, String description,
            String updatedBy, Instant updatedAt) {
        this.key = key;
        this.value = value;
        this.type = type;
        this.description = description;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public String getType() { return type; }
    public String getDescription() { return description; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void change(String value, String updatedBy, Instant updatedAt) {
        this.value = value;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }
}
