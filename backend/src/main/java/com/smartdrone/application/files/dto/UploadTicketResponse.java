package com.smartdrone.application.files.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record UploadTicketResponse(UUID fileId, Upload upload, Instant expiresAt, long maxSizeBytes) {
    // Client gửi multipart/form-data POST tới url: mọi field theo thứ tự, field "file" đặt CUỐI CÙNG.
    public record Upload(String url, String method, String fileFieldName, Map<String, String> fields) { }
}