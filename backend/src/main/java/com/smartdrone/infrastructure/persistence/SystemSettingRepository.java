package com.smartdrone.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import com.smartdrone.domain.entity.SystemSetting;

public interface SystemSettingRepository extends JpaRepository<SystemSetting, String> {
}
