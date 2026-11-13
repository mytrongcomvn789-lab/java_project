package com.smartdrone.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Bản chụp người nhận lúc đặt đơn (không liên kết sống tới bảng addresses). */
@Embeddable
public class RecipientSnapshot {

    @Column(name = "recipient_name", nullable = false, length = 120)
    private String name;

    @Column(name = "recipient_phone", nullable = false, length = 20)
    private String phone;

    @Column(name = "recipient_address_line", nullable = false, length = 500)
    private String addressLine;

    @Column(name = "recipient_latitude", nullable = false)
    private double latitude;

    @Column(name = "recipient_longitude", nullable = false)
    private double longitude;

    protected RecipientSnapshot() { }

    public RecipientSnapshot(String name, String phone, String addressLine,
                             double latitude, double longitude) {
        this.name = name;
        this.phone = phone;
        this.addressLine = addressLine;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getAddressLine() { return addressLine; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
}