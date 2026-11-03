package rbacfixtures;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Chỉ thuộc test: chứng minh filter chặn trước controller. Không đóng gói vào JAR production. */
@RestController
public class BusinessEndpointProbeController {
    @RequestMapping({"/v1/me", "/v1/me/password", "/v1/orders", "/v1/orders/**",
            "/v1/addresses", "/v1/addresses/**", "/v1/stations", "/v1/stations/**",
            "/v1/audit-events", "/v1/analytics/**", "/v1/files/**", "/v1/assistant/**",
            "/v1/integration/**", "/v1/not-in-policy"})
    public ResponseEntity<Void> probe() {
        return ResponseEntity.noContent().build();
    }
}
