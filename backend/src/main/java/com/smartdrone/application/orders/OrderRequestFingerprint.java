package com.smartdrone.application.orders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Collectors;

import com.smartdrone.application.orders.dto.OrderRequest;
import com.smartdrone.application.packages.dto.PackageInput;

/** Dấu vân tay (SHA-256) của nội dung yêu cầu tạo đơn. */
public final class OrderRequestFingerprint {

    private OrderRequestFingerprint() { }

    public static String of(OrderRequest r) {
        PackageInput p = r.packageInfo();
        String canonical = String.join("\u001F",   // ký tự phân cách hiếm gặp trong dữ liệu người dùng
                String.valueOf(r.pickupStationId()),
                String.valueOf(r.dropoffStationId()),
                String.valueOf(r.recipientAddressId()),
                String.valueOf(p.description()),
                String.valueOf(p.weightGrams()),
                String.valueOf(p.lengthCm()),
                String.valueOf(p.widthCm()),
                String.valueOf(p.heightCm()),
                p.photoFileIds().stream().map(UUID::toString).collect(Collectors.joining(",")));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 luôn có trong Java
        }
    }
}