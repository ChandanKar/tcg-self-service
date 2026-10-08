package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Role x grant matrix for SecurityService's view / operate / administer checks and asserts.
 */
class SecurityServiceTest extends AbstractIntegrationTest {

    @Autowired
    private SecurityService security;

    private Object originalEnvAdminScope;
    private Environment env;
    private Environment otherEnv;
    private VmGroup group;

    @BeforeEach
    void setUp() {
        originalEnvAdminScope = ReflectionTestUtils.getField(security, "envAdminScope");
        env = newEnvironment("Secured");
        otherEnv = newEnvironment("Other");
        group = newGroup(env, "web");
    }

    @AfterEach
    void restore() {
        ReflectionTestUtils.setField(security, "envAdminScope", originalEnvAdminScope);
        SecurityContextHolder.clearContext();
    }

    private void actAs(User user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user.getUserId(), null, List.of()));
    }

    private record Expect(boolean view, boolean operate, boolean administer) {
    }

    private void assertMatrix(User user, Expect expected) {
        actAs(user);
        String id = env.getEnvironmentId();
        assertThat(security.canViewEnvironment(id)).as("view").isEqualTo(expected.view());
        assertThat(security.canOperateInEnvironment(id)).as("operate").isEqualTo(expected.operate());
        assertThat(security.canAdministerEnvironment(id)).as("administer").isEqualTo(expected.administer());
        assertThat(security.canManageEnvironmentAccess(id)).as("manage access = administer").isEqualTo(expected.administer());

        assertAssert(() -> security.assertCanView(id), expected.view());
        assertAssert(() -> security.assertCanOperate(id), expected.operate());
        assertAssert(() -> security.assertCanAdminister(id), expected.administer());
    }

    private static void assertAssert(Runnable check, boolean allowed) {
        if (allowed) {
            assertThatCode(check::run).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(check::run).isInstanceOf(UnauthorizedException.class);
        }
    }

    @Test
    void globalAdminCanDoEverything() {
        assertMatrix(newUser("admin@sec.test", true, false), new Expect(true, true, true));
    }

    @Test
    void envAdminWithGlobalScopeAdministersEveryEnvironment() {
        ReflectionTestUtils.setField(security, "envAdminScope", "global");
        assertMatrix(newUser("envadmin@sec.test", false, true), new Expect(true, true, true));
    }

    @Test
    void envAdminWithAssignedScopeNeedsAnAdminGrant() {
        ReflectionTestUtils.setField(security, "envAdminScope", "assigned");
        User envAdmin = newUser("envadmin@sec.test", false, true);

        // Still sees and operates everywhere (global role), but administers nothing without a grant.
        assertMatrix(envAdmin, new Expect(true, true, false));

        grant(envAdmin, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.ADMIN);
        assertMatrix(envAdmin, new Expect(true, true, true));
        assertThat(security.canAdministerEnvironment(otherEnv.getEnvironmentId())).isFalse();
    }

    @Test
    void environmentAdminGranteeAdministersOnlyThatEnvironment() {
        User grantee = newUser("envgrant-admin@sec.test", false, false);
        grant(grantee, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.ADMIN);

        assertMatrix(grantee, new Expect(true, true, true));
        assertThat(security.canAdministerEnvironment(otherEnv.getEnvironmentId())).isFalse();
    }

    @Test
    void environmentUserGranteeOperatesButDoesNotAdminister() {
        User grantee = newUser("envgrant-user@sec.test", false, false);
        grant(grantee, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);

        assertMatrix(grantee, new Expect(true, true, false));
    }

    @Test
    void environmentViewerOnlyViews() {
        User viewer = newUser("envgrant-viewer@sec.test", false, false);
        grant(viewer, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.VIEWER);

        assertMatrix(viewer, new Expect(true, false, false));
    }

    @Test
    void groupUserGranteeViewsAndOperatesButDoesNotAdminister() {
        User grantee = newUser("groupgrant-user@sec.test", false, false);
        grant(grantee, AccessScopeType.GROUP, group.getGroupId(), AccessLevel.USER);

        assertMatrix(grantee, new Expect(true, true, false));
    }

    @Test
    void userWithoutGrantsCanDoNothing() {
        assertMatrix(newUser("nobody@sec.test", false, false), new Expect(false, false, false));
    }

    @Test
    void noSignedInUserCanDoNothing() {
        SecurityContextHolder.clearContext();
        String id = env.getEnvironmentId();
        assertThat(security.canAdministerEnvironment(id)).isFalse();
        assertThat(security.isCurrentUser("anyone")).isFalse();
        assertThatThrownBy(() -> security.assertCanView(id)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void assertSameEnvironmentIs404OnMismatchOrNull() {
        assertThatCode(() -> security.assertSameEnvironment("env-a", "env-a")).doesNotThrowAnyException();
        assertThatThrownBy(() -> security.assertSameEnvironment("env-b", "env-a"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> security.assertSameEnvironment(null, "env-a"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void isCurrentUserMatchesOnlyTheSignedInUser() {
        User me = newUser("me@sec.test", false, false);
        User other = newUser("other@sec.test", false, false);
        actAs(me);

        assertThat(security.isCurrentUser(me.getUserId())).isTrue();
        assertThat(security.isCurrentUser(other.getUserId())).isFalse();
        assertThat(security.isCurrentUser(null)).isFalse();
    }
}
