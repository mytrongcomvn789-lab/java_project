package com.smartdrone.application.eta;

/** Khoảng cách đường chim bay (haversine), đơn vị km. Không tính địa hình, vùng cấm bay hay đường bay thực. */
public final class GeoDistance {
    public static final double EARTH_RADIUS_KM = 6371.0088;

    private GeoDistance() { }

    /** Cùng quy tắc với OrderTrackingService.point(): hữu hạn, trong miền, và không phải đúng 0/0. */
    public static boolean isValid(Double lat, Double lon) {
        return lat != null && lon != null && Double.isFinite(lat) && Double.isFinite(lon)
                && Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && !(lat == 0 && lon == 0);
    }

    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
