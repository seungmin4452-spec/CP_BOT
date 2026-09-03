package com.sunjin.CP_BOT.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 임시 보안 설정.
 *
 * spring-boot-starter-security가 클래스패스에 있으면 기본적으로 모든 요청이 인증을 요구하게 되어
 * Phase 2 Ingestion API를 로컬에서 테스트할 수 없다. Phase 3(권한 기반 필터링)에서 실제 사용자 인증/인가
 * (예: JWT 기반 ADMIN/USER 롤 검사)로 교체하기 전까지는 전체 요청을 허용한다.
 *
 * 이 설정 그대로 운영에 배포하면 절대 안 된다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
