package com.smartdrone.application.files.dto;

import java.time.Instant;
import java.util.UUID;

// url là liên kết tạm thời: không lưu lâu dài, cần xem lại thì xin link mới theo fileId.
public record DownloadLinkResponse(UUID fileId, String url, Instant expiresAt) { }