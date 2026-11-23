package com.smartdrone.application.statistics.dto;

import org.springframework.http.MediaType;

public record ReportFile(String filename, MediaType mediaType, byte[] content) { }
