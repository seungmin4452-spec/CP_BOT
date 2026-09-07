package com.sunjin.CP_BOT.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.accept.HeaderContentNegotiationStrategy;

import java.util.Set;

/**
 * Thymeleaf 화면(세션+폼 로그인)과 curl/외부 API 클라이언트(HTTP Basic) 두 경로를 함께 지원한다.
 * <p>
 * 실제 서비스에서는 이 인메모리 사용자 저장소를 사내 SSO(OIDC/SAML) 연동으로 반드시 교체해야 한다.
 * 브라우저 로그인 결과가 결국 세션 쿠키로 귀결되는 구조이므로, 지금 세션 기반 골격을 잡아두면
 * 나중에 인증 진입점(formLogin)만 {@code oauth2Login()}/SAML로 교체하고 RBAC 필터(AuthenticatedRoles)는
 * 그대로 재사용할 수 있다.
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
            // CSRF는 브라우저가 세션 쿠키를 자동으로 실어 보내는 요청(폼 로그인/화면 fetch)에만 의미가 있다.
            // curl -u 같은 Authorization 헤더 기반 호출은 브라우저가 자동으로 재전송하지 않으므로 CSRF 대상이 아니다.
            .csrf(csrf -> csrf.ignoringRequestMatchers(request -> request.getHeader("Authorization") != null))
            // httpBasic()/formLogin()을 동시에 켜면 등록 순서와 무관하게 기본 인증 진입점이 하나로
            // 고정된다(실측: Basic 팝업이 항상 이김). 그래서 브라우저(Accept: text/html) 요청만
            // 명시적으로 /login 리다이렉트로 보내고, 그 외(curl 등)는 httpBasic의 기본 진입점을 따르게 한다.
            // MediaTypeRequestMatcher는 기본적으로 "*/*"(curl 기본 Accept 값)도 text/html과 호환된다고
            // 판단해 함께 매칭시켜버리므로, ignoredMediaTypes로 "*/*" 와일드카드를 명시적으로 제외해야
            // 실제 브라우저(구체적으로 text/html을 명시하는 Accept 헤더)만 골라낼 수 있다.
            .exceptionHandling(eh -> {
                MediaTypeRequestMatcher htmlRequestMatcher = new MediaTypeRequestMatcher(
                        new HeaderContentNegotiationStrategy(), MediaType.TEXT_HTML);
                htmlRequestMatcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));
                eh.defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"), htmlRequestMatcher);
            })
            .formLogin(form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .permitAll())
            .httpBasic(Customizer.withDefaults())
            .logout(logout -> logout
                .logoutSuccessUrl("/login?logout")
                .permitAll())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/js/**").permitAll()
                // 문서 적재(화면/API 모두)는 ADMIN만 가능 - 아무나 사내 규정을 업로드/수정할 수 없어야 한다.
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/documents", "/api/documents/batch").hasRole("ADMIN")
                .anyRequest().authenticated());
        return http.build();
    }
}
