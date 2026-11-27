package com.smartdrone.application.addresses;

/** Chuyển địa chỉ dạng chữ thành tọa độ. Ném ApiException(ADDRESS_NOT_GEOCODABLE / GEOCODING_UNAVAILABLE) khi lỗi. */
public interface Geocoder {
    record Point(double latitude, double longitude) { }
    Point geocode(String addressLine);
}
