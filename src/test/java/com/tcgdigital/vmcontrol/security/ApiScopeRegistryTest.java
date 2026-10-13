package com.tcgdigital.vmcontrol.security;

import com.tcgdigital.vmcontrol.model.ApiScope;
import com.tcgdigital.vmcontrol.model.ApiToken;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The endpoint-to-scope table for API tokens (E34-T02): first match wins, default deny. */
class ApiScopeRegistryTest {

    private final ApiScopeRegistry registry = new ApiScopeRegistry();

    private Optional<ApiScope> scope(String method, String path) {
        return registry.requiredScope(method, path);
    }

    private static ApiTokenAuthentication token(String... scopes) {
        ApiToken t = new ApiToken();
        t.setTokenId("t-1");
        t.setScopes(String.join(",", scopes));
        return new ApiTokenAuthentication("u-1", t, Set.of());
    }

    @Test
    void environmentsAndOperations() {
        assertThat(scope("GET", "/api/v1/environments")).contains(ApiScope.ENVIRONMENTS_READ);
        assertThat(scope("GET", "/api/v1/environments/e-1/vms/v-1/metrics")).contains(ApiScope.ENVIRONMENTS_READ);
        assertThat(scope("POST", "/api/v1/environments/e-1/operations")).contains(ApiScope.OPERATIONS_WRITE);
        assertThat(scope("POST", "/api/v1/environments/e-1/operations/x-1/cancel")).contains(ApiScope.OPERATIONS_WRITE);
        assertThat(scope("GET", "/api/v1/environments/e-1/operations/x-1")).contains(ApiScope.ENVIRONMENTS_READ);
        assertThat(scope("POST", "/api/v1/environments/e-1/lock/acquire")).contains(ApiScope.OPERATIONS_WRITE);
        assertThat(scope("POST", "/api/v1/environments")).contains(ApiScope.ADMIN);
        assertThat(scope("PUT", "/api/v1/environments/e-1/vms/v-1")).contains(ApiScope.ADMIN);
    }

    @Test
    void schedulesCostAuditAndAccess() {
        assertThat(scope("GET", "/api/v1/automation-rules/r-1/next-runs")).contains(ApiScope.SCHEDULES_READ);
        assertThat(scope("PATCH", "/api/v1/automation-rules/r-1/enabled")).contains(ApiScope.SCHEDULES_WRITE);
        assertThat(scope("POST", "/api/v1/environments/e-1/idle-stop/snooze")).contains(ApiScope.SCHEDULES_WRITE);
        assertThat(scope("GET", "/api/v1/environments/e-1/cost")).contains(ApiScope.COST_READ);
        assertThat(scope("GET", "/api/v1/cost-management/summary")).contains(ApiScope.COST_READ);
        assertThat(scope("POST", "/api/v1/cost-management/rightsizing/apply")).contains(ApiScope.ADMIN);
        assertThat(scope("GET", "/api/v1/audit/logs")).contains(ApiScope.AUDIT_READ);
        assertThat(scope("POST", "/api/v1/environments/e-1/access")).contains(ApiScope.ACCESS_WRITE);
        assertThat(scope("POST", "/api/v1/access-requests/q-1/approve")).contains(ApiScope.ACCESS_WRITE);
        assertThat(scope("GET", "/api/v1/users")).contains(ApiScope.ADMIN);
        assertThat(scope("GET", "/api/v1/users/me")).contains(ApiScope.ENVIRONMENTS_READ);
    }

    @Test
    void tokenManagementSignInChatLinksAndUnknownEndpointsAreDenied() {
        assertThat(scope("GET", "/api/v1/me/api-tokens")).isEmpty();
        assertThat(scope("POST", "/api/v1/me/api-tokens")).isEmpty();
        assertThat(scope("POST", "/api/auth/login")).isEmpty();
        assertThat(scope("POST", "/api/v1/chat-actions/abc")).isEmpty();
        assertThat(scope("PUT", "/api/v1/users/u-1/password")).isEmpty();
        assertThat(scope("GET", "/api/v1/notifications")).isEmpty();
        assertThat(scope("DELETE", "/api/v1/audit/logs")).isEmpty();
        assertThat(scope("GET", "/api/v1/something-new")).isEmpty();
    }

    @Test
    void theAdminScopeOpensEveryMappedEndpointButNotTheDeniedOnes() {
        ApiTokenAuthentication admin = token("admin");
        ApiTokenAuthentication reader = token("environments:read");

        assertThat(registry.allows(admin, "POST", "/api/v1/environments/e-1/operations")).isTrue();
        assertThat(registry.allows(admin, "GET", "/api/v1/me/api-tokens")).isFalse();
        assertThat(registry.allows(reader, "GET", "/api/v1/environments")).isTrue();
        assertThat(registry.allows(reader, "POST", "/api/v1/environments/e-1/operations")).isFalse();
        assertThat(reader.getAuthorities()).extracting(Object::toString).contains("API_SCOPE_environments:read");
    }
}
