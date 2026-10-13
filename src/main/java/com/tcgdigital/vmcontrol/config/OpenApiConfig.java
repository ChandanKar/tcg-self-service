package com.tcgdigital.vmcontrol.config;

import com.tcgdigital.vmcontrol.model.ApiScope;
import com.tcgdigital.vmcontrol.security.ApiScopeRegistry;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Optional;

/**
 * The OpenAPI description for token users (E34-T04): the bearer scheme {@code apiToken} applies to
 * every operation, and each operation carries {@code x-required-scope}, the scope a token needs for
 * it according to {@link ApiScopeRegistry} ({@code session-only} when tokens cannot call it). The
 * spec itself is only served to signed-in users and tokens.
 */
@Configuration
public class OpenApiConfig {

    public static final String SCHEME = "apiToken";
    public static final String SCOPE_EXTENSION = "x-required-scope";

    @Bean
    public OpenAPI vmControlOpenApi() {
        return new OpenAPI()
                .info(new Info().title("VM Control API").version("v1")
                        .description("Personal or service API tokens: 'Authorization: Bearer vmc_...'. Each operation lists the "
                                + "scope its token needs (" + SCOPE_EXTENSION + "); the token's owner must also be allowed to do it."))
                .components(new Components().addSecuritySchemes(SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("vmc token")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME));
    }

    @Bean
    public OpenApiCustomizer requiredScopeCustomizer(ApiScopeRegistry registry) {
        return openApi -> {
            if (openApi.getPaths() == null) return;
            openApi.getPaths().forEach((path, item) -> {
                for (Map.Entry<PathItem.HttpMethod, Operation> e : item.readOperationsMap().entrySet()) {
                    Optional<ApiScope> scope = registry.requiredScope(e.getKey().name(), path);
                    e.getValue().addExtension(SCOPE_EXTENSION, scope.map(ApiScope::value).orElse("session-only"));
                }
            });
        };
    }
}
