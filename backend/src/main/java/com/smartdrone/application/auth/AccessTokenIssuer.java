package com.smartdrone.application.auth;

import com.smartdrone.domain.entity.User;

// Cổng (port) tạo access token. Bản cài đặt JWT nằm ở infrastructure/service.
public interface AccessTokenIssuer {

    String issue(User user);

    // Số giây access token còn hiệu lực (trả về cho client ở trường expiresIn).
    long ttlSeconds();
}
