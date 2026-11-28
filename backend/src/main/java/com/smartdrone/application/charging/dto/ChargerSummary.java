package com.smartdrone.application.charging.dto;

public record ChargerSummary(int total, int empty, int charging, int full, int maintenance, int online) { }
