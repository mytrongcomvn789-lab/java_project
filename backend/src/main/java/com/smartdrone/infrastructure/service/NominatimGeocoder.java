package com.smartdrone.infrastructure.service;

import java.net.http.HttpClient;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import com.smartdrone.application.addresses.Geocoder;
import com.smartdrone.application.common.ApiException;
import com.smartdrone.application.common.ErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Geocoding bằng Nominatim (OpenStreetMap). Chính sách của Nominatim yêu cầu User-Agent định danh ứng dụng
 * và tối đa ~1 request/giây; đủ cho thao tác thêm/sửa địa chỉ của khách. Khi triển khai thật nên trỏ
 * smartdrone.geocoding.base-url tới máy chủ riêng hoặc dịch vụ trả phí. Địa chỉ gửi đi là dữ liệu cá nhân của
 * người nhận: chỉ gửi addressLine, không gửi tên/SĐT.
 */
@Component
public class NominatimGeocoder implements Geocoder {
    private static final Logger log = LoggerFactory.getLogger(NominatimGeocoder.class);
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public NominatimGeocoder(
            @Value("${smartdrone.geocoding.base-url:https://nominatim.openstreetmap.org}") String baseUrl,
            @Value("${smartdrone.geocoding.user-agent:SmartDroneDelivery/1.0}") String userAgent) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.client = RestClient.builder().requestFactory(factory).baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent).build();
    }

    @Override
    public Point geocode(String addressLine) {
        String body;
        try {
            body = client.get().uri(u -> u.path("/search").queryParam("q", addressLine)
                    .queryParam("format", "jsonv2").queryParam("limit", 1).build())
                    .retrieve().body(String.class);
        } catch (Exception e) {
            log.warn("Geocoding thất bại: {}", e.getClass().getSimpleName());
            throw new ApiException(ErrorCode.GEOCODING_UNAVAILABLE);
        }
        try {
            JsonNode first = mapper.readTree(body == null ? "[]" : body).path(0);
            if (first.isMissingNode() || first.isNull()) throw new ApiException(ErrorCode.ADDRESS_NOT_GEOCODABLE);
            double lat = Double.parseDouble(first.path("lat").asString());
            double lon = Double.parseDouble(first.path("lon").asString());
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) throw new NumberFormatException();
            return new Point(lat, lon);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("Geocoding trả dữ liệu không hợp lệ");
            throw new ApiException(ErrorCode.GEOCODING_UNAVAILABLE);
        }
    }
}
