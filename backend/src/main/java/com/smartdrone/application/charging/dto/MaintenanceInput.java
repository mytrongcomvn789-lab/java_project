package com.smartdrone.application.charging.dto;

import jakarta.validation.constraints.NotNull;

public record MaintenanceInput(@NotNull Boolean maintenance) { }
