package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.model.ApiScope;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which scope an API token needs for an endpoint (E34-T02): an explicit table, first match wins,
 * and anything not in it is denied to tokens. Reads are GET/HEAD, everything else is a write. The
 * admin scope passes every mapped endpoint. A scope only opens the door: role rules and
 * environment access still apply, so a token never does more than its owner may.
 */
@Component
public class ApiScopeRegistry {

    /** Never reachable with a token, whatever its scopes. */
    public static final Optional<ApiScope> DENIED = Optional.empty();

    /** The OpenAPI description (E34-T04): any token may read it. */
    public static final String DOCS_PATH = "/v3/api-docs";

    private enum Kind { READ, WRITE, ANY }

    /** {@code scope} null means denied to tokens. */
    private record Rule(Kind kind, String pattern, ApiScope scope) {
    }

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD");
    private final AntPathMatcher matcher = new AntPathMatcher();

    private static final List<Rule> RULES = List.of(
            // The MCP server for AI assistants (E36-T01): assistant:read only, not even admin opens it
            new Rule(Kind.ANY, "/mcp", ApiScope.ASSISTANT_READ),
            new Rule(Kind.ANY, "/mcp/**", ApiScope.ASSISTANT_READ),

            // Never with a token: token management, sign-in, signed chat links, password changes
            new Rule(Kind.ANY, "/api/v1/me/api-tokens/**", null),
            new Rule(Kind.ANY, "/api/auth/**", null),
            new Rule(Kind.ANY, "/api/v1/chat-actions/**", null),
            new Rule(Kind.ANY, "/api/v1/users/*/password", null),

            // Operating environments
            new Rule(Kind.READ, "/api/v1/environments/*/operations/**", ApiScope.ENVIRONMENTS_READ),
            new Rule(Kind.WRITE, "/api/v1/environments/*/operations/**", ApiScope.OPERATIONS_WRITE),
            new Rule(Kind.READ, "/api/v1/environments/*/lock/**", ApiScope.ENVIRONMENTS_READ),
            new Rule(Kind.WRITE, "/api/v1/environments/*/lock/**", ApiScope.OPERATIONS_WRITE),
            new Rule(Kind.READ, "/api/v1/environments/*/lease/**", ApiScope.ENVIRONMENTS_READ),
            new Rule(Kind.WRITE, "/api/v1/environments/*/lease/**", ApiScope.OPERATIONS_WRITE),
            new Rule(Kind.WRITE, "/api/v1/environments/*/notify-stop", ApiScope.OPERATIONS_WRITE),
            new Rule(Kind.READ, "/api/v1/approval-requests/**", ApiScope.ENVIRONMENTS_READ),
            new Rule(Kind.WRITE, "/api/v1/approval-requests/**", ApiScope.OPERATIONS_WRITE),
            new Rule(Kind.READ, "/api/v1/provisioning-requests/**", ApiScope.ENVIRONMENTS_READ),
            new Rule(Kind.WRITE, "/api/v1/provisioning-requests/**", ApiScope.OPERATIONS_WRITE),

            // Schedules
            new Rule(Kind.READ, "/api/v1/environments/*/idle-stop/**", ApiScope.SCHEDULES_READ),
            new Rule(Kind.WRITE, "/api/v1/environments/*/idle-stop/**", ApiScope.SCHEDULES_WRITE),
            new Rule(Kind.READ, "/api/v1/automation-rules/**", ApiScope.SCHEDULES_READ),
            new Rule(Kind.WRITE, "/api/v1/automation-rules/**", ApiScope.SCHEDULES_WRITE),
            new Rule(Kind.READ, "/api/v1/holiday-calendars/**", ApiScope.SCHEDULES_READ),
            new Rule(Kind.WRITE, "/api/v1/holiday-calendars/**", ApiScope.SCHEDULES_WRITE),

            // Access
            new Rule(Kind.READ, "/api/v1/environments/*/access/**", ApiScope.ENVIRONMENTS_READ),
            new Rule(Kind.WRITE, "/api/v1/environments/*/access/**", ApiScope.ACCESS_WRITE),
            new Rule(Kind.READ, "/api/v1/environments/*/access-requests/**", ApiScope.ENVIRONMENTS_READ),
            new Rule(Kind.WRITE, "/api/v1/environments/*/access-requests/**", ApiScope.ACCESS_WRITE),
            new Rule(Kind.READ, "/api/v1/access-grants/**", ApiScope.ENVIRONMENTS_READ),
            new Rule(Kind.WRITE, "/api/v1/access-grants/**", ApiScope.ACCESS_WRITE),
            new Rule(Kind.READ, "/api/v1/access-requests/**", ApiScope.ENVIRONMENTS_READ),
            new Rule(Kind.WRITE, "/api/v1/access-requests/**", ApiScope.ACCESS_WRITE),
            new Rule(Kind.WRITE, "/api/v1/lease-extension-requests/**", ApiScope.ACCESS_WRITE),

            // Cost of an environment
            new Rule(Kind.READ, "/api/v1/environments/*/cost", ApiScope.COST_READ),
            new Rule(Kind.READ, "/api/v1/environments/*/cost-anomalies", ApiScope.COST_READ),
            new Rule(Kind.READ, "/api/v1/environments/*/eks-workload-cost", ApiScope.COST_READ),
            new Rule(Kind.READ, "/api/v1/environments/*/savings", ApiScope.COST_READ),
            new Rule(Kind.READ, "/api/v1/environments/*/budget", ApiScope.COST_READ),
            new Rule(Kind.READ, "/api/v1/environments/*/spot-summary", ApiScope.COST_READ),
            new Rule(Kind.READ, "/api/v1/environments/*/carbon", ApiScope.COST_READ),

            // Environments, their groups and VMs: reading; changing the registry is admin work
            new Rule(Kind.READ, "/api/v1/environments/**", ApiScope.ENVIRONMENTS_READ),
            new Rule(Kind.WRITE, "/api/v1/environments/**", ApiScope.ADMIN),
            new Rule(Kind.READ, "/api/v1/dashboard/**", ApiScope.ENVIRONMENTS_READ),
            new Rule(Kind.READ, "/api/v1/templates/**", ApiScope.ENVIRONMENTS_READ),
            new Rule(Kind.WRITE, "/api/v1/templates/**", ApiScope.ADMIN),
            new Rule(Kind.READ, "/api/v1/rds/**", ApiScope.ENVIRONMENTS_READ),
            new Rule(Kind.READ, "/api/v1/users/me", ApiScope.ENVIRONMENTS_READ),
            new Rule(Kind.READ, "/api/v1/users/me/**", ApiScope.ENVIRONMENTS_READ),

            // Cost management
            new Rule(Kind.READ, "/api/v1/cost-management/**", ApiScope.COST_READ),
            new Rule(Kind.WRITE, "/api/v1/cost-management/**", ApiScope.ADMIN),
            new Rule(Kind.READ, "/api/v1/savings/**", ApiScope.COST_READ),
            new Rule(Kind.WRITE, "/api/v1/savings/**", ApiScope.ADMIN),
            new Rule(Kind.READ, "/api/v1/budgets/**", ApiScope.COST_READ),
            new Rule(Kind.WRITE, "/api/v1/budgets/**", ApiScope.ADMIN),
            new Rule(Kind.READ, "/api/v1/cost-anomalies/**", ApiScope.COST_READ),
            new Rule(Kind.WRITE, "/api/v1/cost-anomalies/**", ApiScope.ADMIN),
            new Rule(Kind.READ, "/api/v1/waste/**", ApiScope.COST_READ),
            new Rule(Kind.WRITE, "/api/v1/waste/**", ApiScope.ADMIN),

            // Audit
            new Rule(Kind.READ, "/api/v1/audit/**", ApiScope.AUDIT_READ),

            // Administration
            new Rule(Kind.ANY, "/api/v1/users/**", ApiScope.ADMIN),
            new Rule(Kind.ANY, "/api/v1/teams/**", ApiScope.ADMIN),
            new Rule(Kind.ANY, "/api/v1/aws-accounts/**", ApiScope.ADMIN),
            new Rule(Kind.ANY, "/api/v1/settings/**", ApiScope.ADMIN),
            new Rule(Kind.ANY, "/api/v1/chat-channels/**", ApiScope.ADMIN),
            new Rule(Kind.ANY, "/api/v1/monitoring/**", ApiScope.ADMIN),
            new Rule(Kind.ANY, "/api/v1/approval-policies/**", ApiScope.ADMIN),
            new Rule(Kind.ANY, "/api/v1/integrations/**", ApiScope.ADMIN),
            new Rule(Kind.ANY, "/api/v1/directory/**", ApiScope.ADMIN),
            new Rule(Kind.ANY, "/api/v1/webhooks/**", ApiScope.ADMIN),
            new Rule(Kind.ANY, "/api/ec2/**", ApiScope.ADMIN)
    );

    /** The scope a token needs, or empty when tokens may not call this endpoint at all. */
    public Optional<ApiScope> requiredScope(String method, String path) {
        boolean read = READ_METHODS.contains(method == null ? "" : method.toUpperCase());
        for (Rule rule : RULES) {
            boolean kindMatches = rule.kind() == Kind.ANY || (rule.kind() == Kind.READ) == read;
            if (kindMatches && matcher.match(rule.pattern(), path)) {
                return Optional.ofNullable(rule.scope());
            }
        }
        return DENIED;
    }

    /** Whether a token with these scopes may call the endpoint. */
    public boolean allows(ApiTokenAuthentication token, String method, String path) {
        if (path.equals(DOCS_PATH) || path.startsWith(DOCS_PATH + "/")) {
            return "GET".equalsIgnoreCase(method);
        }
        Optional<ApiScope> required = requiredScope(method, path);
        if (required.isPresent() && required.get() == ApiScope.ASSISTANT_READ) {
            return token.hasScope(ApiScope.ASSISTANT_READ);
        }
        return required.isPresent() && (token.hasScope(required.get()) || token.hasScope(ApiScope.ADMIN));
    }
}
