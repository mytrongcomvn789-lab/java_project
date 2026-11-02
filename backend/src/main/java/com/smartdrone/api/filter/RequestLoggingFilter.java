package com.smartdrone.api.filter;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Ghi 1 dòng log cho mỗi request: METHOD /đường-dẫn -> mã trạng thái (thời gian)
@Component
@Order(Ordered.HIGHEST_PRECEDENCE) // chạy đầu tiên, trước các filter khác (sau này có JWT filter)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(request, response); // cho request đi tiếp tới controller
        } finally {
            // finally: dù controller thành công hay lỗi vẫn ghi log
            long ms = System.currentTimeMillis() - start;
            log.info("{} {} -> {} ({} ms)",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), ms);
        }
    }

    // Không ghi log cho Swagger cho đỡ rối
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/swagger-ui") || path.startsWith("/api/v3/api-docs");
    }
}