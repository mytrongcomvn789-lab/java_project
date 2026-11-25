package com.smartdrone.application.ai;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Envelope thống nhất của mọi phản hồi /v1/ai/**. result là null khi degraded=true (fallback).
 * mock=true hoặc degraded=true nghĩa là KHÔNG phải kết quả AI thật.
 */
public record AiResult(JsonNode result, String model, boolean mock, boolean degraded, AiSource source,
                       AiUsage usage, UUID requestId, Instant generatedAt, String fallbackReason) { }
