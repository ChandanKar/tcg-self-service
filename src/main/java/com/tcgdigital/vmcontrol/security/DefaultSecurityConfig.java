package com.tcgdigital.vmcontrol.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * Default security configuration used when Entra ID authentication is disabled.
 * Shows login page but permits all requests without authentication for local development and testing.
 * Note: Method security (@PreAuthorize) is not enabled here - it's only active
 * when EntraidSecurityConfig is used.
 */
@Configuration
@Profile({"dev", "test"})
@ConditionalOnProperty(name = "entraid.enabled", havingValue = "false")
public class DefaultSecurityConfig {

    /**
     * In dev/test mode, reads an X-User-Id header and sets it as the security principal.
     * UserService.getCurrentUser() handles String principals by looking up by ID or email.
     * This filter only runs when entraid.enabled=false.
     */
    private static class DevUserHeaderFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws IOException, ServletException {
            String userId = request.getHeader("X-User-Id");
            if (userId != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userId, null, Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            chain.doFilter(request, response);
        }
    }

    @Value("${security.csp.report-only:true}")
    private boolean cspReportOnly;

    @Bean
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .addFilterBefore(new DevUserHeaderFilter(), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/", "/login", "/login.html", "/css/**", "/js/**", "/vendor/**", "/logo/**", "/images/**",
                    "/static/**", "/error", "/logout").permitAll()
                .anyRequest().permitAll()
            )
            .csrf(csrf -> csrf.disable())
            // Local logout only (no Entra session in dev); with CSRF off it also accepts GET.
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout=true")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"))
            .headers(SecurityHeaders.apply(cspReportOnly));

        return http.build();
    }
}
