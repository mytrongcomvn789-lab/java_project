package com.smartdrone.api.config;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * Task 5.4: hoàn thiện tài liệu OpenAPI.
 * Swagger UI: http://localhost:5000/api/swagger-ui.html (chọn nhóm ở góc trên bên phải).
 * - "business-v1": API JWT cho web/mobile (/v1/**, trừ admin và integration).
 * - "admin-v1": API quản trị, vẫn JWT nhưng chỉ SYSTEM_ADMIN (/v1/admin/**).
 * - "integration-v1": API cho hệ thống ngoài, xác thực bằng API key, KHÔNG dùng JWT (/v1/integration/**).
 *
 * Versioning: version nằm trong URI (/v1/...). Thay đổi không phá vỡ hợp đồng (thêm trường, thêm endpoint)
 * vẫn ở /v1. Thay đổi phá vỡ hợp đồng (đổi kiểu trường, đổi ý nghĩa, xóa trường) phải lên /v2; /v1 vẫn chạy
 * song song đến khi mọi client (web, mobile, hệ thống ngoài đang cầm API key) đã chuyển xong.
 *
 * Error envelope (mọi lỗi, mọi nhóm): { "type", "title", "status", "detail", "code", "message", "traceId" }.
 * "code" là mã ổn định để code xử lý theo (xem ErrorCode), "message"/"detail" có thể đổi câu chữ giữa các bản.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI smartDroneOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SmartDroneDelivery API")
                        .description("""
                                API nền tảng quản lý giao hàng bằng drone.

                                Versioning: version nằm trong đường dẫn (/v1/...). Bản hiện tại là v1; \
                                thay đổi phá vỡ hợp đồng sẽ lên /v2 và /v1 vẫn chạy song song một thời gian.

                                Lỗi: mọi lỗi trả cùng một khuôn dạng JSON (type, title, status, detail, \
                                code, message, traceId). Dựa vào "code" để xử lý theo chương trình, \
                                "message"/"detail" chỉ để hiển thị.

                                Xác thực: web/mobile dùng Bearer JWT (scheme bearerAuth). Hệ thống ngoài \
                                dùng API key qua header X-Api-Key (scheme apiKeyAuth), chỉ gọi được \
                                nhóm "integration-v1" và chỉ các endpoint khớp scope đã được cấp.""")
                        .version("v1"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT"))
                        .addSecuritySchemes("apiKeyAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(com.smartdrone.api.security.ApiKeyAuthenticationFilter.HEADER)
                                .description("Khuôn dạng: {id}.{secret}, lấy từ POST /v1/admin/api-keys lúc cấp.")));
    }

    @Bean
    public GroupedOpenApi businessApi() {
        return GroupedOpenApi.builder().group("business-v1")
                .pathsToMatch("/v1/**")
                .pathsToExclude("/v1/admin/**", "/v1/integration/**")
                .build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("admin-v1").pathsToMatch("/v1/admin/**").build();
    }

    @Bean
    public GroupedOpenApi integrationApi() {
        return GroupedOpenApi.builder().group("integration-v1").pathsToMatch("/v1/integration/**").build();
    }
}