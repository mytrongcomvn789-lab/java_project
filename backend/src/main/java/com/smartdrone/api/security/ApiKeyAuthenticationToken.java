package com.smartdrone.api.security;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import com.smartdrone.domain.enums.IntegrationScope;

/** Principal cho request đi qua API key, không phải người dùng. name() = id của key (để ghi log/audit). */
public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {
    private final UUID keyId;

    public ApiKeyAuthenticationToken(UUID keyId, Set<IntegrationScope> scopes) {
        super(scopes.stream().map(s -> new SimpleGrantedAuthority("SCOPE_" + s.name())).toList());
        this.keyId = keyId;
        setAuthenticated(true);
    }

    @Override public Object getCredentials() { return null; }
    @Override public Object getPrincipal() { return keyId; }
    @Override public String getName() { return keyId.toString(); }
}