package com.smartdrone.application.packages.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Thông tin gói hàng nằm TRONG yêu cầu tạo/sửa đơn (trường "package" của OrderRequest, task 3.6).
 * Không có endpoint /packages riêng ở v1. Ràng buộc định dạng ở đây; giới hạn số (trọng lượng tối đa,
 * kích thước tối đa, số ảnh) do nhóm chốt nên nằm ở cấu hình và được PackageService kiểm tra.
 */
public record PackageInput(
        @NotBlank(message = "Mô tả gói hàng không được để trống")
        @Size(max = 500, message = "Mô tả gói hàng tối đa 500 ký tự") String description,
        @NotNull(message = "Cần nhập trọng lượng")
        @Min(value = 1, message = "Trọng lượng phải lớn hơn 0") Integer weightGrams,
        @NotNull(message = "Cần nhập chiều dài")
        @DecimalMin(value = "0", inclusive = false, message = "Chiều dài phải lớn hơn 0") Double lengthCm,
        @NotNull(message = "Cần nhập chiều rộng")
        @DecimalMin(value = "0", inclusive = false, message = "Chiều rộng phải lớn hơn 0") Double widthCm,
        @NotNull(message = "Cần nhập chiều cao")
        @DecimalMin(value = "0", inclusive = false, message = "Chiều cao phải lớn hơn 0") Double heightCm,
        List<@NotNull(message = "fileId ảnh không được rỗng") UUID> photoFileIds) {

    public PackageInput {
        description = description == null ? null : description.strip();
        // Không dùng List.copyOf: nó ném NPE khi có phần tử null trước khi @NotNull kịp báo lỗi 400.
        photoFileIds = photoFileIds == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(photoFileIds));
    }

    // Hợp đồng không cho thuộc tính thừa (ví dụ id/orderId/ownerId giả mạo).
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) {
        throw new IllegalArgumentException("Thuộc tính không được hỗ trợ");
    }
}
