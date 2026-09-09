package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Group-aware resolution in {@link SecurityService} (access-grants step 3). Pure unit tests —
 * no Spring context, no database.
 */
@ExtendWith(MockitoExtension.class)
class SecurityServiceGroupAccessTest {

    private static final String ENV = "env-1";
    private static final String G1 = "grp-1";
    private static final String G2 = "grp-2";
    private static final String G3 = "grp-3";
    private static final String UID = "user-1";

    @Mock private UserService userService;
    @Mock private EnvironmentAccessService accessService;
    @Mock private VmGroupRepository vmGroupRepository;

    private SecurityService security;

    @BeforeEach
    void setUp() {
        security = new SecurityService(userService, accessService, vmGroupRepository);
    }

    // ---- helpers ----

    private User plainUser() {
        User u = User.fromAzureAd("oid-1", "u@example.com", "U One");
        u.setUserId(UID);
        return u;
    }

    private User adminUser() {
        User u = plainUser();
        u.setAdmin(true);
        return u;
    }

    private User envAdminUser() {
        User u = plainUser();
        u.setEnvAdmin(true);
        return u;
    }

    private VmGroup group(String id) {
        Environment env = new Environment();
        env.setEnvironmentId(ENV);
        VmGroup g = new VmGroup();
        g.setGroupId(id);
        g.setEnvironment(env);
        return g;
    }

    private EnvironmentAccess grant(AccessScopeType type, String scopeId, AccessLevel level) {
        EnvironmentAccess ea = new EnvironmentAccess();
        ea.setScopeType(type);
        ea.setScopeId(scopeId);
        ea.setAccessLevel(level);
        return ea;
    }

    // ---- effectiveGroupLevel composition ----

    @Test
    void noUser_isNoAccess() {
        when(userService.getCurrentUser()).thenReturn(null);
        assertThat(security.effectiveGroupLevel(G1)).isNull();
        assertThat(security.hasGroupAccess(G1)).isFalse();
    }

    @Test
    void plainUserWithNothing_isNoAccess() {
        when(userService.getCurrentUser()).thenReturn(plainUser());
        when(vmGroupRepository.findById(G1)).thenReturn(Optional.of(group(G1)));
        when(accessService.getActiveGrant(UID, AccessScopeType.ENVIRONMENT, ENV)).thenReturn(Optional.empty());
        when(accessService.getActiveGrant(UID, AccessScopeType.GROUP, G1)).thenReturn(Optional.empty());

        assertThat(security.effectiveGroupLevel(G1)).isNull();
        assertThat(security.hasGroupAccess(G1)).isFalse();
    }

    @Test
    void groupGrantOnly_gives_thatLevel() {
        when(userService.getCurrentUser()).thenReturn(plainUser());
        when(vmGroupRepository.findById(G1)).thenReturn(Optional.of(group(G1)));
        when(accessService.getActiveGrant(UID, AccessScopeType.ENVIRONMENT, ENV)).thenReturn(Optional.empty());
        when(accessService.getActiveGrant(UID, AccessScopeType.GROUP, G1))
                .thenReturn(Optional.of(grant(AccessScopeType.GROUP, G1, AccessLevel.USER)));

        assertThat(security.effectiveGroupLevel(G1)).isEqualTo(AccessLevel.USER);
        assertThat(security.hasGroupAccessLevel(G1, AccessLevel.USER)).isTrue();
        assertThat(security.hasGroupAccessLevel(G1, AccessLevel.ADMIN)).isFalse();
    }

    @Test
    void envGrantCoversGroup_evenWithNoGroupGrant() {
        when(userService.getCurrentUser()).thenReturn(plainUser());
        when(vmGroupRepository.findById(G1)).thenReturn(Optional.of(group(G1)));
        when(accessService.getActiveGrant(UID, AccessScopeType.ENVIRONMENT, ENV))
                .thenReturn(Optional.of(grant(AccessScopeType.ENVIRONMENT, ENV, AccessLevel.USER)));
        when(accessService.getActiveGrant(UID, AccessScopeType.GROUP, G1)).thenReturn(Optional.empty());

        assertThat(security.effectiveGroupLevel(G1)).isEqualTo(AccessLevel.USER);
    }

    @Test
    void grantsCompose_toTheMax() {
        when(userService.getCurrentUser()).thenReturn(plainUser());
        when(vmGroupRepository.findById(G1)).thenReturn(Optional.of(group(G1)));
        when(accessService.getActiveGrant(UID, AccessScopeType.ENVIRONMENT, ENV))
                .thenReturn(Optional.of(grant(AccessScopeType.ENVIRONMENT, ENV, AccessLevel.VIEWER)));
        when(accessService.getActiveGrant(UID, AccessScopeType.GROUP, G1))
                .thenReturn(Optional.of(grant(AccessScopeType.GROUP, G1, AccessLevel.USER)));

        // env=VIEWER, group=USER -> USER on this group
        assertThat(security.effectiveGroupLevel(G1)).isEqualTo(AccessLevel.USER);
    }

    @Test
    void adminAndEnvAdmin_getAdmin_onAnyGroup_withoutHittingGrants() {
        when(userService.getCurrentUser()).thenReturn(adminUser());
        assertThat(security.effectiveGroupLevel(G1)).isEqualTo(AccessLevel.ADMIN);

        when(userService.getCurrentUser()).thenReturn(envAdminUser());
        assertThat(security.effectiveGroupLevel(G2)).isEqualTo(AccessLevel.ADMIN);
    }

    // ---- getVisibleGroupIds ----

    @Test
    void visibleGroups_envAccess_seesEveryGroup() {
        when(userService.getCurrentUser()).thenReturn(plainUser());
        when(vmGroupRepository.findByEnvironmentId(ENV)).thenReturn(List.of(group(G1), group(G2), group(G3)));
        when(accessService.getActiveGrant(UID, AccessScopeType.ENVIRONMENT, ENV))
                .thenReturn(Optional.of(grant(AccessScopeType.ENVIRONMENT, ENV, AccessLevel.VIEWER)));

        assertThat(security.getVisibleGroupIds(ENV)).containsExactlyInAnyOrder(G1, G2, G3);
    }

    @Test
    void visibleGroups_groupScopedUser_seesOnlyGrantedGroups() {
        when(userService.getCurrentUser()).thenReturn(plainUser());
        when(vmGroupRepository.findByEnvironmentId(ENV)).thenReturn(List.of(group(G1), group(G2), group(G3)));
        when(accessService.getActiveGrant(UID, AccessScopeType.ENVIRONMENT, ENV)).thenReturn(Optional.empty());
        when(accessService.getActiveGroupGrantLevels(UID, List.of(G1, G2, G3)))
                .thenReturn(Map.of(G2, AccessLevel.USER));

        assertThat(security.getVisibleGroupIds(ENV)).containsExactly(G2);
    }

    @Test
    void visibleGroups_noAccess_isEmpty() {
        when(userService.getCurrentUser()).thenReturn(plainUser());
        when(vmGroupRepository.findByEnvironmentId(ENV)).thenReturn(List.of(group(G1), group(G2)));
        when(accessService.getActiveGrant(UID, AccessScopeType.ENVIRONMENT, ENV)).thenReturn(Optional.empty());
        when(accessService.getActiveGroupGrantLevels(UID, List.of(G1, G2))).thenReturn(Map.of());

        assertThat(security.getVisibleGroupIds(ENV)).isEmpty();
    }

    @Test
    void visibleGroups_admin_seesEveryGroup_withoutCheckingGrants() {
        when(userService.getCurrentUser()).thenReturn(adminUser());
        when(vmGroupRepository.findByEnvironmentId(ENV)).thenReturn(List.of(group(G1), group(G2)));

        assertThat(security.getVisibleGroupIds(ENV)).containsExactlyInAnyOrder(G1, G2);
    }

    // ---- canManageGroupAccess ----

    @Test
    void canManageGroupAccess_unknownGroup_isDenied() {
        lenient().when(userService.getCurrentUser()).thenReturn(adminUser());
        when(vmGroupRepository.findById("nope")).thenReturn(Optional.empty());

        assertThat(security.canManageGroupAccess("nope")).isFalse();
    }

    @Test
    void canManageGroupAccess_envAdmin_ofThatEnvironment_isAllowed() {
        when(userService.getCurrentUser()).thenReturn(envAdminUser());
        when(vmGroupRepository.findById(G1)).thenReturn(Optional.of(group(G1)));

        assertThat(security.canManageGroupAccess(G1)).isTrue();
    }

    @Test
    void canManageGroupAccess_plainUser_isDenied() {
        when(userService.getCurrentUser()).thenReturn(plainUser());
        when(vmGroupRepository.findById(G1)).thenReturn(Optional.of(group(G1)));
        when(accessService.hasAccessLevel(ENV, UID, AccessLevel.ADMIN)).thenReturn(false);

        assertThat(security.canManageGroupAccess(G1)).isFalse();
    }

    // ---- operate / view checks (step 5) ----

    @Test
    void canOperateInEnvironment_true_forEnvUser() {
        when(userService.getCurrentUser()).thenReturn(plainUser());
        when(accessService.getActiveGrant(UID, AccessScopeType.ENVIRONMENT, ENV))
                .thenReturn(Optional.of(grant(AccessScopeType.ENVIRONMENT, ENV, AccessLevel.USER)));

        assertThat(security.canOperateInEnvironment(ENV)).isTrue();
    }

    @Test
    void canOperateInEnvironment_true_forGroupUser() {
        when(userService.getCurrentUser()).thenReturn(plainUser());
        when(accessService.getActiveGrant(UID, AccessScopeType.ENVIRONMENT, ENV)).thenReturn(Optional.empty());
        when(vmGroupRepository.findByEnvironmentId(ENV)).thenReturn(List.of(group(G1), group(G2)));
        when(accessService.getActiveGroupGrantLevels(UID, List.of(G1, G2)))
                .thenReturn(Map.of(G2, AccessLevel.USER));

        assertThat(security.canOperateInEnvironment(ENV)).isTrue();
    }

    @Test
    void canOperateInEnvironment_false_forGroupViewerOnly() {
        when(userService.getCurrentUser()).thenReturn(plainUser());
        when(accessService.getActiveGrant(UID, AccessScopeType.ENVIRONMENT, ENV)).thenReturn(Optional.empty());
        when(vmGroupRepository.findByEnvironmentId(ENV)).thenReturn(List.of(group(G1)));
        when(accessService.getActiveGroupGrantLevels(UID, List.of(G1)))
                .thenReturn(Map.of(G1, AccessLevel.VIEWER));

        assertThat(security.canOperateInEnvironment(ENV)).isFalse();
    }

    @Test
    void hasGroupAccessLevelForUser_loadsUserById_andComposes() {
        when(userService.getUserById(UID)).thenReturn(plainUser());
        when(vmGroupRepository.findById(G1)).thenReturn(Optional.of(group(G1)));
        when(accessService.getActiveGrant(UID, AccessScopeType.ENVIRONMENT, ENV)).thenReturn(Optional.empty());
        when(accessService.getActiveGrant(UID, AccessScopeType.GROUP, G1))
                .thenReturn(Optional.of(grant(AccessScopeType.GROUP, G1, AccessLevel.USER)));

        assertThat(security.hasGroupAccessLevelForUser(UID, G1, AccessLevel.USER)).isTrue();
        assertThat(security.hasGroupAccessLevelForUser(UID, G1, AccessLevel.ADMIN)).isFalse();
    }

    @Test
    void hasGroupAccessLevelForUser_unknownUser_isFalse() {
        when(userService.getUserById("ghost")).thenThrow(new RuntimeException("no such user"));
        assertThat(security.hasGroupAccessLevelForUser("ghost", G1, AccessLevel.USER)).isFalse();
    }
}
