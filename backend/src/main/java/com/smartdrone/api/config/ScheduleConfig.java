package com.smartdrone.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.smartdrone.application.scheduling.ScheduleProperties;

// Bật cấu hình lên lịch giao hàng từ application.yml.
@Configuration
@EnableConfigurationProperties(ScheduleProperties.class)
public class ScheduleConfig { }