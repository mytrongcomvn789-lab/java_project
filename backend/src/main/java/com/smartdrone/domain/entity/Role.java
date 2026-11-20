package com.smartdrone.domain.entity;

import java.util.HashSet;
import java.util.Set;

import com.smartdrone.domain.enums.RoleName;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

// Vai trò (5 actor). Dữ liệu được nạp sẵn bởi DataSeeder.
@Entity
@Table(name = "roles")
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 30)
    private RoleName name;

    // Quyền của vai trò (bảng role_permissions). Chỉ đọc/ghi trong transaction (lazy).
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_code"))
    private Set<Permission> permissions = new HashSet<>();

    protected Role() {
        // JPA yêu cầu constructor không tham số
    }

    public Role(RoleName name) {
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public RoleName getName() {
        return name;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }
}
