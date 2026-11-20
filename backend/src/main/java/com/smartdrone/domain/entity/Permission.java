package com.smartdrone.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Một quyền chức năng (vd ACCOUNT_MANAGE). Danh mục quyền được nạp bởi AccessSettingsSeeder.
@Entity
@Table(name = "permissions")
public class Permission {

    @Id
    @Column(length = 60)
    private String code;

    @Column(name = "group_name", nullable = false, length = 80)
    private String groupName;

    @Column(nullable = false, length = 150)
    private String label;

    protected Permission() { }

    public Permission(String code, String groupName, String label) {
        this.code = code;
        this.groupName = groupName;
        this.label = label;
    }

    public String getCode() { return code; }
    public String getGroupName() { return groupName; }
    public String getLabel() { return label; }

    @Override
    public boolean equals(Object o) {
        return o instanceof Permission p && code.equals(p.code);
    }

    @Override
    public int hashCode() { return code.hashCode(); }
}
