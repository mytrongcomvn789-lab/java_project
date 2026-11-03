package com.smartdrone.api.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.smartdrone.api.error.ProblemJson;
import com.smartdrone.application.common.ErrorCode;
import com.smartdrone.api.security.AccountJwtAuthenticationConverter;
import com.smartdrone.api.security.RbacPolicy;
import com.smartdrone.domain.enums.RoleName;

import jakarta.servlet.DispatcherType;

// Task 2.6: xác thực JWT + quyền trên URL + @PreAuthorize tại service.
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            AccountJwtAuthenticationConverter authenticationConverter) throws Exception {
        // 401: chưa gửi token / token sai / token hết hạn
        AuthenticationEntryPoint entryPoint = (request, response, ex) -> {
            String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            ErrorCode code = message.contains("expired") ? ErrorCode.TOKEN_EXPIRED : ErrorCode.AUTH_REQUIRED;
            ProblemJson.write(response, code);
        };
        // 403: đã đăng nhập nhưng không đủ quyền
        AccessDeniedHandler deniedHandler = (request, response, ex) ->
                ProblemJson.write(response, ErrorCode.FORBIDDEN);

        http
                .csrf(AbstractHttpConfigurer::disable) // API stateless dùng Bearer token, không dùng cookie
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> {
                    auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/health",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // logout công khai: refresh token chính là bằng chứng, và access token có thể đã hết hạn
                        .requestMatchers(HttpMethod.POST,
                                "/v1/auth/register", "/v1/auth/login",
                                "/v1/auth/refresh", "/v1/auth/logout").permitAll();
                    for (RbacPolicy.Rule rule : RbacPolicy.rules()) {
                        auth.requestMatchers(rule.method(), rule.path())
                            .hasAnyRole(rule.roles().stream().map(RoleName::name).toArray(String[]::new));
                    }
                    // Endpoint mới phải khai báo quyền rõ ràng, tránh tự mở cho mọi người đã đăng nhập.
                    auth.anyRequest().denyAll();
                })
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler));
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
