package com.tcgdigital.vmcontrol.security;

import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.web.header.writers.ContentSecurityPolicyHeaderWriter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Response security headers shared by both security configurations (C2, C3).
 *
 * <p>The Content-Security-Policy allows scripts only from this origin, so an injected inline
 * handler or {@code <script>} is blocked by the browser even if an escaping bug slips through.
 * Styles keep {@code 'unsafe-inline'} because templates and ECharts set style attributes.
 * Violations are posted to {@link CspReportController}. Swagger UI is excluded from the policy
 * rather than relaxing it for everyone.
 */
public final class SecurityHeaders {

    public static final String REPORT_PATH = "/api/csp-report";

    public static final String POLICY = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self' 'unsafe-inline'",
            "img-src 'self' data: blob:",
            "font-src 'self' data:",
            "connect-src 'self'",
            "object-src 'none'",
            "base-uri 'self'",
            "frame-ancestors 'none'",
            "form-action 'self'",
            "report-uri " + REPORT_PATH);

    private static final RequestMatcher SWAGGER = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui/**"),
            PathPatternRequestMatcher.withDefaults().matcher("/swagger-ui.html"),
            PathPatternRequestMatcher.withDefaults().matcher("/v3/api-docs/**"));

    private SecurityHeaders() {
    }

    /**
     * @param reportOnly send {@code Content-Security-Policy-Report-Only} (violations are reported,
     *                   nothing is blocked) instead of the enforced header
     */
    public static Customizer<HeadersConfigurer<HttpSecurity>> apply(boolean reportOnly) {
        ContentSecurityPolicyHeaderWriter csp = new ContentSecurityPolicyHeaderWriter(POLICY);
        csp.setReportOnly(reportOnly);
        return headers -> headers
                .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(new NegatedRequestMatcher(SWAGGER), csp))
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                .referrerPolicy(referrer -> referrer
                        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .contentTypeOptions(Customizer.withDefaults());
    }
}
