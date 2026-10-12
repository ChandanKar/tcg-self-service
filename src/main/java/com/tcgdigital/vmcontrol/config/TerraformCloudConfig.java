package com.tcgdigital.vmcontrol.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The HCP Terraform / Terraform Enterprise API client (E32-T06): base URL and a team or
 * organization token from the environment ({@code TFC_BASE_URL}, {@code TFC_TOKEN}). Only created
 * with {@code provisioning.terraform.enabled=true}. The token is attached as a bearer header and
 * never logged.
 */
@Configuration
@ConditionalOnProperty(name = "provisioning.terraform.enabled", havingValue = "true")
public class TerraformCloudConfig {

    public static final String JSON_API = "application/vnd.api+json";

    @Bean
    public RestClient terraformRestClient(@Value("${provisioning.terraform.base-url:https://app.terraform.io}") String baseUrl,
                                          @Value("${provisioning.terraform.token:}") String token,
                                          @Value("${provisioning.terraform.timeout-ms:15000}") long timeoutMs) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("provisioning.terraform.enabled=true needs TFC_TOKEN");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder()
                .baseUrl(baseUrl.replaceAll("/+$", ""))
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + token)
                .defaultHeader("Content-Type", JSON_API)
                .defaultHeader("Accept", JSON_API)
                .build();
    }
}
