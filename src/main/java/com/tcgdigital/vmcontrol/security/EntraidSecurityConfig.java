package com.tcgdigital.vmcontrol.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
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
                // allow static resources, health/error endpoints, oauth callback, h2 console, and Swagger UI
                .requestMatchers("/css/**", "/js/**", "/images/**", "/static/**", "/error", "/h2-console/**", "/login/**", "/oauth2/**",
                    "/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**", "/swagger-resources/**", "/webjars/**").permitAll()
                // require authentication for all other requests (including /home)
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo -> userInfo
                    .oidcUserService(customOAuth2UserService)
                )
                .defaultSuccessUrl("/home", true)
            )
            // CSRF configuration for API endpoints
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/**", "/h2-console/**")
            )
            // allow H2 console to render in a frame
            .headers(headers -> headers.frameOptions(frame -> frame.disable()));

        return http.build();
    }
}
