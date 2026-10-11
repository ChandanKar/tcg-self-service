package com.tcgdigital.vmcontrol.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/** The HTTP client for Slack/Teams webhooks (E24-T01): short timeouts, no base URL. */
@Configuration
public class ChatConfig {

    @Bean
    public RestClient chatRestClient(@Value("${chat.timeout-ms:5000}") long timeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return RestClient.builder().requestFactory(requestFactory).build();
    }
}
