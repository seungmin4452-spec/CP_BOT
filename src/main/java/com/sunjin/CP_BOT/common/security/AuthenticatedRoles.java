package com.sunjin.CP_BOT.common.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * 인증된 사용자의 Spring Security 권한("ROLE_ADMIN" 등)을 RBAC 필터에 쓸 역할명("ADMIN")으로 변환한다.
 * 검색/채팅 API가 역할을 항상 이 방식으로만 서버 측에서 얻도록 해서, 클라이언트가 역할을 자칭하는 것을 막는다.
 */
public final class AuthenticatedRoles {

    private AuthenticatedRoles() {
    }

    public static Set<String> extract(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(authority -> authority.replaceFirst("^ROLE_", ""))
                .collect(Collectors.toSet());
    }
}
