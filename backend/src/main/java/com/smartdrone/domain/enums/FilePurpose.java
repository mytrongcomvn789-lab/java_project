package com.smartdrone.domain.enums;

public enum FilePurpose {
    PACKAGE_PHOTO("package-photos", RoleName.CUSTOMER),             // ảnh gói hàng do khách tải
    CONFIRMATION_PHOTO("confirmation-photos", RoleName.STATION_OPERATOR); // ảnh xác nhận do nhân viên trạm tải

    private final String keyPrefix;
    private final RoleName uploaderRole;

    FilePurpose(String keyPrefix, RoleName uploaderRole) {
        this.keyPrefix = keyPrefix;
        this.uploaderRole = uploaderRole;
    }

    public String keyPrefix() { return keyPrefix; }
    public boolean allows(RoleName role) { return role == uploaderRole; }
}