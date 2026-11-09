package com.smartdrone.application.files;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

// Cổng (port) tới kho đối tượng. Bản cài đặt MinIO nằm ở infrastructure/service.
public interface ObjectStorage {

    record PresignedUpload(String url, Map<String, String> fields) { }

    record StoredObjectInfo(long size) { }

    void ensureBucket();

    // Form POST có policy: MinIO tự từ chối file ngoài khoảng dung lượng hoặc sai Content-Type.
    PresignedUpload presignUpload(String objectKey, String contentType, long maxBytes, Instant expiresAt);

    String presignDownload(String objectKey, String contentType, Duration ttl);

    Optional<StoredObjectInfo> stat(String objectKey);

    byte[] readHead(String objectKey, int length);

    void remove(String objectKey);
}