package com.tcgdigital.vmcontrol.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Wiring for Microsoft Graph directory calls, used by admin user onboarding
 * (see {@code docs/graph-user-onboarding.md}).
 *
 * <p>Active only when {@code graph.directory.enabled=true}. By default nothing here is
 * created, and the onboarding flow uses its manual-entry fallback. When enabled, the
 * Azure app registration must additionally hold the <em>application</em> permission
 * {@code User.ReadBasic.All} with tenant admin consent.
 *
 * <p>Auth uses the {@code graph} client-credentials registration
 * ({@code spring.security.oauth2.client.registration.graph.*}); the access token is
 * minted, cached and refreshed by
 * {@link AuthorizedClientServiceOAuth2AuthorizedClientManager} — this app never handles
 * it directly beyond attaching the bearer header.
 */
@Configuration
@ConditionalOnProperty(name = "graph.directory.enabled", havingValue = "true")
public class GraphConfig {

    /** Registration id declared in application.properties. */
    public static final String GRAPH_CLIENT_REGISTRATION_ID = "graph";

    /** Stable principal name — client-credentials has no end user; this keys the token cache. */
    private static final String GRAPH_PRINCIPAL = "graph-directory";

    @Bean
    public AuthorizedClientServiceOAuth2AuthorizedClientManager graphAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository) {

        OAuth2AuthorizedClientService authorizedClientService =
                new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    @Bean
    public RestClient graphRestClient(
            @Value("${graph.api.base-url:https://graph.microsoft.com/v1.0}") String baseUrl,
            @Value("${graph.api.timeout-ms:5000}") long timeoutMs,
            AuthorizedClientServiceOAuth2AuthorizedClientManager graphAuthorizedClientManager) {

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofMillis(timeoutMs));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        ClientHttpRequestInterceptor bearerToken = (request, body, execution) -> {
            OAuth2AuthorizedClient client = graphAuthorizedClientManager.authorize(
                    OAuth2AuthorizeRequest.withClientRegistrationId(GRAPH_CLIENT_REGISTRATION_ID)
                            .principal(GRAPH_PRINCIPAL)
                            .build());
            if (client != null) {
                request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
            }
            return execution.execute(request, body);
        };

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(bearerToken)
                .build();
    }
}
