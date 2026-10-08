package com.tcgdigital.vmcontrol.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import com.tcgdigital.vmcontrol.repository.UserRepository;

import java.time.Duration;
import org.springframework.context.annotation.Bean;

@Configuration
@ConditionalOnProperty(name = "entraid.enabled", havingValue = "true")
@EnableMethodSecurity(prePostEnabled = true)
public class EntraidSecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;
    private final boolean cspReportOnly;
    private final UserRepository userRepository;
    private final Duration sessionAbsoluteTimeout;

    public EntraidSecurityConfig(CustomOAuth2UserService customOAuth2UserService,
                                 @Value("${security.csp.report-only:true}") boolean cspReportOnly,
                                 UserRepository userRepository,
                                 @Value("${security.session.absolute-timeout:PT12H}") Duration sessionAbsoluteTimeout) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.cspReportOnly = cspReportOnly;
        this.userRepository = userRepository;
        this.sessionAbsoluteTimeout = sessionAbsoluteTimeout;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authorize -> authorize
                // allow static resources, login page, health/error endpoints, oauth callback, CSP reports and Swagger UI
                .requestMatchers("/", "/login", "/login.html", "/css/**", "/js/**", "/vendor/**", "/logo/**", "/images/**", "/static/**",
                    "/error", "/login/**", "/oauth2/**", "/logout",
                    "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/swagger-resources/**", "/webjars/**",
                    "/api/auth/login", "/api/auth/options", SecurityHeaders.REPORT_PATH,
                    "/actuator/health", "/actuator/health/**").permitAll()
                // require authentication for all other requests (including /home)
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .userInfoEndpoint(userInfo -> userInfo
                    .oidcUserService(customOAuth2UserService)
                )
                .defaultSuccessUrl("/home", true)
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .clearAuthentication(true)
            )
            // Return JSON 403 for API requests instead of default behavior
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    String requestUri = request.getRequestURI();
                    if (requestUri.startsWith("/api/")) {
                        response.setStatus(HttpStatus.UNAUTHORIZED.value());
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.getWriter().write(
                            "{\"error\":\"Unauthorized\",\"message\":\"Authentication required\"}"
                        );
                    } else {
                        response.sendRedirect("/login");
                    }
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(
                        "{\"error\":\"Forbidden\",\"message\":\"You do not have permission to perform this action.\"}"
                    );
                })
            )
            // Session management - create session for authentication
            // maximumSessions(1) was removed: it never matched OIDC principals (no stable equals).
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .sessionFixation(fixation -> fixation.changeSessionId())
            )
            // Reload the user on every request: deactivation, role changes and the absolute
            // session lifetime apply on the next request (H10).
            .addFilterBefore(new CurrentUserRefreshFilter(userRepository, sessionAbsoluteTimeout),
                AuthorizationFilter.class)
            // CSRF configuration for API endpoints
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/**")  // Allow /api/auth/login without CSRF token
            )
            // Content-Security-Policy, X-Frame-Options DENY, Referrer-Policy, nosniff
            .headers(SecurityHeaders.apply(cspReportOnly));

        return http.build();
    }
}
