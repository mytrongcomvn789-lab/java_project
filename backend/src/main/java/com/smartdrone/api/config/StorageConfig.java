package com.smartdrone.api.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.smartdrone.application.files.ObjectStorage;
import com.smartdrone.application.files.StorageProperties;
import com.smartdrone.infrastructure.service.MinioObjectStorage;

// Task 3.3: kho tệp MinIO. Thiếu cấu hình thì app vẫn chạy, chỉ các API tệp trả 503.
@Configuration
@EnableScheduling
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {
    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    public ObjectStorage objectStorage(StorageProperties props) {
        return new MinioObjectStorage(props);
    }

    @Bean
    public ApplicationRunner storageBucketInitializer(ObjectStorage storage, StorageProperties props) {
        return args -> {
            if (!props.isConfigured()) {
                log.warn("Chưa cấu hình MinIO (MINIO_ACCESS_KEY/MINIO_SECRET_KEY): API tệp sẽ trả 503");
            } else if (props.autoCreateBucket()) {
                try {
                    storage.ensureBucket();
                } catch (RuntimeException ex) {
                    log.warn("Không kiểm tra/tạo được bucket lúc khởi động: {}", ex.getMessage());
                }
            }
        };
    }
}