package com.smartdrone.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.smartdrone.application.pickup.PickupProperties;

@Configuration
@EnableConfigurationProperties(PickupProperties.class)
public class PickupConfig { }