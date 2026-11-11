package com.smartdrone.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.smartdrone.application.packages.PackageProperties;

// nạp giới hạn gói hàng từ cấu hình.
@Configuration
@EnableConfigurationProperties(PackageProperties.class)
public class PackageConfig { }
