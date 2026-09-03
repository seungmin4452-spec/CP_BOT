package com.sunjin.CP_BOT.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic 인증 + 인메모리 사용자(ADMIN/USER)로 RBAC를 실제로 검증할 수 있게 한다.
 * <p>
 * 실제 서비스에서는 이 인메모리 사용자 저장소를 사내 IdP/LDAP 연동이나 JWT 기반 인증으로 반드시
 * 교체해야 한다. 여기서는 "인증된 사용자의 역할이 검색 쿼리의 RBAC 사전 필터에 그대로 쓰인다"는
 * Phase 3의 핵심 동작을 로컬에서 재현/검증하기 위한 최소 구현이다.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(
            PasswordEncoder passwordEncoder,
            @Value("${app.security.demo-admin-password:admin-local-dev-only}") String adminPassword,
            @Value("${app.security.demo-user-password:user-local-dev-only}") String userPassword) {

        UserDetails admin = User.withUsername("admin")
                .password(passwordEncoder.encode(adminPassword))
                .roles("ADMIN")
                .build();
        UserDetails user = User.withUsername("user")
                .password(passwordEncoder.encode(userPassword))
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(admin, user);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                // 문서 적재는 ADMIN만 가능 - 아무나 사내 규정을 업로드/수정할 수 없어야 한다.
                .requestMatchers(HttpMethod.POST, "/api/documents").hasRole("ADMIN")
                .anyRequest().authenticated());
        return http.build();
    }
}
