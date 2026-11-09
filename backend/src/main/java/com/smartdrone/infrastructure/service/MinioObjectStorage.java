package com.smartdrone.infrastructure.service;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.application.files.ObjectStorage;
import com.smartdrone.application.files.StorageProperties;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PostPolicy;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;

public class MinioObjectStorage implements ObjectStorage {
    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorage.class);
    private static final Set<String> MISSING = Set.of("NoSuchKey", "NoSuchObject", "NoSuchBucket");

    private final StorageProperties props;
    private final MinioClient client; // gọi nội bộ (stat/get/remove)
    private final MinioClient signer; // chỉ ký URL với host công khai, không gọi mạng (đã đặt region)

    public MinioObjectStorage(StorageProperties props) {
        this.props = props;
        if (props.isConfigured()) {
            this.client = MinioClient.builder().endpoint(props.endpoint())
                    .credentials(props.accessKey(), props.secretKey()).region(props.region()).build();
            this.signer = MinioClient.builder().endpoint(props.effectivePublicEndpoint())
                    .credentials(props.accessKey(), props.secretKey()).region(props.region()).build();
        } else {
            this.client = null;
            this.signer = null;
        }
    }

    @Override
    public void ensureBucket() {
        MinioClient c = require();
        try {
            if (!c.bucketExists(BucketExistsArgs.builder().bucket(props.bucket()).build())) {
                c.makeBucket(MakeBucketArgs.builder().bucket(props.bucket()).region(props.region()).build());
            }
        } catch (Exception ex) {
            throw unavailable("ensureBucket", ex);
        }
    }

    @Override
    public PresignedUpload presignUpload(String objectKey, String contentType, long maxBytes, Instant expiresAt) {
        MinioClient s = require();
        try {
            PostPolicy policy = new PostPolicy(props.bucket(), ZonedDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
            policy.addEqualsCondition("key", objectKey);
            policy.addEqualsCondition("Content-Type", contentType);
            policy.addContentLengthRangeCondition(1, maxBytes);
            Map<String, String> fields = signer.getPresignedPostFormData(policy);
            String url = trimSlash(props.effectivePublicEndpoint()) + "/" + props.bucket();
            return new PresignedUpload(url, fields);
        } catch (Exception ex) {
            throw unavailable("presignUpload", ex);
        }
    }

    @Override
    public String presignDownload(String objectKey, String contentType, Duration ttl) {
        require();
        try {
            return signer.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET).bucket(props.bucket()).object(objectKey)
                    .expiry((int) ttl.toSeconds(), TimeUnit.SECONDS)
                    .extraQueryParams(Map.of(
                            "response-content-type", contentType,
                            "response-content-disposition", "inline",
                            "response-cache-control", "private, no-store"))
                    .build());
        } catch (Exception ex) {
            throw unavailable("presignDownload", ex);
        }
    }

    @Override
    public Optional<StoredObjectInfo> stat(String objectKey) {
        MinioClient c = require();
        try {
            var stat = c.statObject(StatObjectArgs.builder().bucket(props.bucket()).object(objectKey).build());
            return Optional.of(new StoredObjectInfo(stat.size()));
        } catch (ErrorResponseException ex) {
            if (MISSING.contains(ex.errorResponse().code())) return Optional.empty();
            throw unavailable("stat", ex);
        } catch (Exception ex) {
            throw unavailable("stat", ex);
        }
    }

    @Override
    public byte[] readHead(String objectKey, int length) {
        MinioClient c = require();
        try (InputStream in = c.getObject(GetObjectArgs.builder().bucket(props.bucket()).object(objectKey)
                .offset(0L).length((long) length).build())) {
            return in.readNBytes(length);
        } catch (Exception ex) {
            throw unavailable("readHead", ex);
        }
    }

    @Override
    public void remove(String objectKey) {
        MinioClient c = require();
        try {
            c.removeObject(RemoveObjectArgs.builder().bucket(props.bucket()).object(objectKey).build());
        } catch (Exception ex) {
            throw unavailable("remove", ex);
        }
    }

    private MinioClient require() {
        if (client == null) {
            throw new ApiException(ErrorCode.STORAGE_UNAVAILABLE, "Kho tệp chưa được cấu hình (thiếu MINIO_ACCESS_KEY/MINIO_SECRET_KEY)");
        }
        return client;
    }

    // Log tên thao tác + loại lỗi, không log secret, URL đã ký hay object key.
    private ApiException unavailable(String operation, Exception ex) {
        log.error("Lỗi kho tệp ở thao tác {}: {}", operation, ex.getClass().getSimpleName());
        return new ApiException(ErrorCode.STORAGE_UNAVAILABLE);
    }

    private static String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}