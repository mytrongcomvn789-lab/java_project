package com.smartdrone.api.security;

import static com.smartdrone.domain.enums.IntegrationScope.*;
import static org.springframework.http.HttpMethod.*;

import java.util.List;

import org.springframework.http.HttpMethod;

import com.smartdrone.domain.enums.IntegrationScope;

/** 5.4: endpoint dành cho hệ thống ngoài, xác thực bằng API key (không phải JWT). Mỗi route đúng một scope. */
public final class IntegrationPolicy {
    private IntegrationPolicy() { }

    public record Rule(HttpMethod method, String path, IntegrationScope scope) { }

    private static Rule rule(HttpMethod method, String path, IntegrationScope scope) {
        return new Rule(method, path, scope);
    }

    public static List<Rule> rules() {
        return RULES;
    }

    private static final List<Rule> RULES = List.of(
            rule(GET, "/v1/integration/orders/{id}/status", ORDERS_READ),
            rule(GET, "/v1/integration/stations", STATIONS_READ)
    );
    // Thêm endpoint ngoài mới: thêm 1 rule ở đây + 1 scope ở IntegrationScope + controller trong
    // IntegrationController. KHÔNG thêm endpoint nghiệp vụ đang có JWT (RbacPolicy) vào đây.
}