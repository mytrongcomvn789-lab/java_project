package com.smartdrone.api.security;

import java.util.List;
import java.util.UUID;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.smartdrone.domain.entity.User;
import com.smartdrone.domain.enums.RoleName;
import com.smartdrone.infrastructure.persistence.UserRepository;

/** Chạy SAU khi Spring kiểm chữ ký/issuer/audience/exp. Không tin quyền đã bị thu hồi trong JWT cũ. */
@Component
public class AccountJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserRepository users;

    public AccountJwtAuthenticationConverter(UserRepository users) {
        this.users = users;
    }

    @Override
    @Transactional(readOnly = true)
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID id;
        RoleName claimedRole;
        try {
            id = UUID.fromString(jwt.getSubject());
            // Một role duy nhất, không nhận danh sách hoặc chuỗi ghép để nâng quyền.
            Object claim = jwt.getClaims().get("role");
            if (!(claim instanceof String value)) {
                throw invalidToken();
            }
            claimedRole = RoleName.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw invalidToken();
        }
        User user = users.findWithRoleById(id).orElseThrow(AccountJwtAuthenticationConverter::invalidToken);
        Object versionClaim = jwt.getClaims().get("accessVersion");
        // JWT của 2.5 chưa có claim này: chỉ chấp nhận khi tài khoản vẫn ở version 0.
        boolean versionMatches = versionClaim == null ? user.getAccessVersion() == 0
                : versionClaim instanceof Number value
                    && value.doubleValue() == user.getAccessVersion();
        if (jwt.getExpiresAt() == null || !versionMatches || !user.isEnabled()
                || user.getRole() == null || user.getRole().getName() != claimedRole) {
            throw invalidToken();
        }
        return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().getName().name())), id.toString());
    }

    private static InvalidBearerTokenException invalidToken() {
        return new InvalidBearerTokenException("Tài khoản hoặc quyền truy cập không còn hợp lệ");
    }
}
