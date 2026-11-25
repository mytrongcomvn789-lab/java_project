package com.smartdrone.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.smartdrone.application.eta.EtaProperties;

// Bật cấu hình ETA (7.2) từ application.yml.
@Configuration
@EnableConfigurationProperties(EtaProperties.class)
public class EtaConfig { }
