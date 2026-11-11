package com.smartdrone.application.packages.dto;

import java.util.List;
import java.util.UUID;

import com.smartdrone.domain.entity.OrderPackage;

// Chỉ trả fileId của ảnh (không bao giờ trả signed URL / object key): web xin link xem qua /v1/files/{id}/download-link.
public record PackageView(String description, int weightGrams, double lengthCm, double widthCm, double heightCm,
                          List<UUID> photoFileIds) {
    public static PackageView from(OrderPackage p) {
        return new PackageView(p.getDescription(), p.getWeightGrams(), p.getLengthCm(), p.getWidthCm(),
                p.getHeightCm(), List.copyOf(p.getPhotoFileIds()));
    }
}
