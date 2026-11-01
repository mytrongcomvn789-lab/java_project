package com.smartdrone.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

// Swagger UI: http://localhost:5000/api/swagger-ui.html
// Khai báo sẵn kiểu xác thực Bearer JWT để task 2.5 dùng.
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI smartDroneOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("SmartDroneDelivery API")
                        .description("API nền tảng quản lý giao hàng bằng drone")
                        .version("0.1.0"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
