package com.smartdrone.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartdrone.domain.entity.Permission;

public interface PermissionRepository extends JpaRepository<Permission, String> {
}
