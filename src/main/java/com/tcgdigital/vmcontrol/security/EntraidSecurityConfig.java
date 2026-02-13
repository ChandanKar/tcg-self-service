package com.tcgdigital.vmcontrol.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.context.annotation.Bean;

@Configuration
@ConditionalOnProperty(name = "entraid.enabled", havingValue = "true")
public class EntraidSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authorize -> authorize
                // allow static resources, health/error endpoints, oauth callback and h2 console
                .requestMatchers("/css/**", "/js/**", "/images/**", "/static/**", "/error", "/h2-console/**", "/login/**", "/oauth2/**").permitAll()
                // require authentication for all other requests (including /home)
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .defaultSuccessUrl("/home", true)
            )
            // allow H2 console to render in a frame
            .headers(headers -> headers.frameOptions(frame -> frame.disable()));

        return http.build();
    }
}
