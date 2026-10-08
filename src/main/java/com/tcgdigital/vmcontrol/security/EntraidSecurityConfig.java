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
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
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
    private final EntraLogoutSuccessHandler logoutSuccessHandler;

    public EntraidSecurityConfig(CustomOAuth2UserService customOAuth2UserService,
                                 @Value("${security.csp.report-only:true}") boolean cspReportOnly,
                                 UserRepository userRepository,
                                 @Value("${security.session.absolute-timeout:PT12H}") Duration sessionAbsoluteTimeout,
                                 @Value("${entraid.end-session-uri:}") String endSessionUri,
                                 @Value("${entraid.post-logout-redirect-uri:}") String postLogoutRedirectUri) {
        this.customOAuth2UserService = customOAuth2UserService;
        this.cspReportOnly = cspReportOnly;
        this.userRepository = userRepository;
        this.sessionAbsoluteTimeout = sessionAbsoluteTimeout;
        this.logoutSuccessHandler = new EntraLogoutSuccessHandler(endSessionUri, postLogoutRedirectUri);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authorize -> authorize
                // allow static resources, login page, health/error endpoints, oauth callback, CSP reports and Swagger UI
                .requestMatchers("/", "/login", "/login.html", "/css/**", "/js/**", "/vendor/**", "/logo/**", "/images/**", "/static/**",
                    "/error", "/login/**", "/oauth2/**",
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
            // POST /logout only (CSRF is on, so LogoutFilter ignores GET); Entra users are then
            // sent to the Entra end-session endpoint so the Microsoft session ends too.
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessHandler(logoutSuccessHandler)
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
            // CSRF on for the API too (session-cookie auth): double-submit cookie XSRF-TOKEN, sent back
            // as the X-XSRF-TOKEN header by core/api-client.js and login.js. Browser CSP reports
            // cannot carry the header.
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers(SecurityHeaders.REPORT_PATH)
            )
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            // Content-Security-Policy, X-Frame-Options DENY, Referrer-Policy, nosniff
            .headers(SecurityHeaders.apply(cspReportOnly));

        return http.build();
    }
}
