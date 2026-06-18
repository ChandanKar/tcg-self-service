package com.tcgdigital.vmcontrol.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.context.annotation.Bean;

@Configuration
@ConditionalOnProperty(name = "entraid.enabled", havingValue = "true")
@EnableMethodSecurity(prePostEnabled = true)
public class EntraidSecurityConfig {

    private final CustomOAuth2UserService customOAuth2UserService;

    public EntraidSecurityConfig(CustomOAuth2UserService customOAuth2UserService) {
        this.customOAuth2UserService = customOAuth2UserService;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authorize -> authorize
                // allow static resources, login page, health/error endpoints, oauth callback, h2 console, and Swagger UI
                .requestMatchers("/", "/login", "/login.html", "/css/**", "/js/**", "/logo/**", "/images/**", "/static/**",
                    "/error", "/h2-console/**", "/login/**", "/oauth2/**", "/logout",
                    "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/swagger-resources/**", "/webjars/**",
                    "/api/auth/login",
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
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                .maximumSessions(1)  // Allow only 1 session per user
                .maxSessionsPreventsLogin(false)  // New login invalidates old session
            )
            // CSRF configuration for API endpoints
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/**", "/h2-console/**")  // Allow /api/auth/login without CSRF token
            )
            // allow H2 console to render in a frame
            .headers(headers -> headers.frameOptions(frame -> frame.disable()));

        return http.build();
    }
}
