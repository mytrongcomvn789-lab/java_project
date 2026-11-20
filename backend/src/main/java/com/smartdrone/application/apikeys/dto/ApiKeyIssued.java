package com.smartdrone.application.apikeys.dto;

/** Kết quả cấp mới hoặc xoay vòng. secret chỉ xuất hiện trong lần gọi NÀY, không bao giờ đọc lại được sau đó. */
public record ApiKeyIssued(ApiKeyView key, String secret) { }