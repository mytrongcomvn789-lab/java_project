package com.smartdrone.application.packages;

import org.springframework.boot.context.properties.ConfigurationProperties;

// Giới hạn gói hàng (app.order-package.*). KHÔNG có giá trị mặc định trong code: nhóm phải chốt rồi
// khai báo trong application.yml. Thiếu hoặc <= 0 thì ứng dụng dừng ngay lúc khởi động với thông báo rõ ràng.
@ConfigurationProperties(prefix = "app.order-package")
public record PackageProperties(
        Integer maxWeightGrams,
        Double maxLengthCm,
        Double maxWidthCm,
        Double maxHeightCm,
        Integer minPhotos,
        Integer maxPhotos) {

    public PackageProperties {
        requirePositive("max-weight-grams", maxWeightGrams);
        requirePositive("max-length-cm", maxLengthCm);
        requirePositive("max-width-cm", maxWidthCm);
        requirePositive("max-height-cm", maxHeightCm);
        requirePositive("max-photos", maxPhotos);
        if (minPhotos == null || minPhotos < 0 || minPhotos > maxPhotos) {
            throw new IllegalStateException(
                    "app.order-package.min-photos chưa cấu hình hoặc không nằm trong khoảng 0..max-photos");
        }
    }

    private static void requirePositive(String name, Number value) {
        if (value == null || value.doubleValue() <= 0) {
            throw new IllegalStateException("app.order-package." + name + " chưa được cấu hình (phải > 0)");
        }
    }
}
