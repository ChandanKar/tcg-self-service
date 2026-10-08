package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import com.tcgdigital.vmcontrol.dto.AccessGrantRequestDTO;
import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.dto.GrantAccessDTO;
import com.tcgdigital.vmcontrol.dto.UpdateAccessGrantDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRequestRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for EnvironmentAccessService.
 */
@Transactional
class EnvironmentAccessServiceTest extends AbstractIntegrationTest {

    @Autowired
    private EnvironmentAccessService accessService;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EnvironmentAccessRepository accessRepository;

    @Autowired
    private EnvironmentAccessRequestRepository requestRepository;

    @Autowired
    private VmGroupRepository groupRepository;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private Environment testEnvironment;
    private User requesterUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        // Clean up
        requestRepository.deleteAll();
        accessRepository.deleteAll();
        groupRepository.deleteAll();
        userRepository.deleteAll();

        // Create test environment (reuse existing or create new)
        testEnvironment = environmentRepository.findByName("test-env").orElseGet(() -> {
            Environment env = new Environment();
            env.setEnvironmentId(UUID.randomUUID().toString());
            env.setName("test-env");
            env.setDisplayName("Test Environment");
            env.setIsActive(true);
            return environmentRepository.save(env);
        });

        // Create test users
        requesterUser = User.fromAzureAd("requester-oid", "requester@example.com", "Requester User");
        requesterUser = userRepository.save(requesterUser);

        adminUser = User.fromAzureAd("admin-oid", "admin@example.com", "Admin User");
        adminUser.setAdmin(true);
        adminUser = userRepository.save(adminUser);
    }

    // ============= Access Scope (V20) Tests =============

    @Test
    @DisplayName("A direct env grant is ENVIRONMENT-scoped and resolvable by the scope-aware finders")
    void directGrant_isEnvironmentScoped() {
        GrantAccessDTO dto = new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.USER, null, "step-1 check");
        EnvironmentAccess granted = accessService.grantAccess(
                testEnvironment.getEnvironmentId(), adminUser.getUserId(), dto);

        assertThat(granted.getScopeType()).isEqualTo(AccessScopeType.ENVIRONMENT);
        assertThat(granted.getScopeId()).isEqualTo(testEnvironment.getEnvironmentId());
        assertThat(granted.getInitiation()).isEqualTo(AccessInitiation.DIRECT);

        Timestamp now = new Timestamp(System.currentTimeMillis());
        assertThat(accessRepository.findActiveByUserAndScope(
                requesterUser.getUserId(), AccessScopeType.ENVIRONMENT,
                testEnvironment.getEnvironmentId(), now)).isPresent();
        assertThat(accessRepository.findActiveByUserAndScope(
                requesterUser.getUserId(), AccessScopeType.GROUP, "no-such-group", now)).isEmpty();
        assertThat(accessRepository.findActiveGroupGrantsForUser(
                requesterUser.getUserId(), List.of("no-such-group"), now)).isEmpty();

        // The strict environment-authorization checks still see the grant.
        assertThat(accessRepository.hasAccess(
                testEnvironment.getEnvironmentId(), requesterUser.getUserId(), now)).isTrue();
        assertThat(accessRepository.hasAccessLevel(
                testEnvironment.getEnvironmentId(), requesterUser.getUserId(), AccessLevel.USER, now)).isTrue();
    }

    @Test
    @DisplayName("An access request is ENVIRONMENT-scoped by default")
    void request_isEnvironmentScopedByDefault() {
        EnvironmentAccessRequest request = accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(), requesterUser.getUserId(),
                new CreateAccessRequestDTO(AccessLevel.USER, "need it", 7));

        assertThat(request.getScopeType()).isEqualTo(AccessScopeType.ENVIRONMENT);
        assertThat(request.getScopeId()).isEqualTo(testEnvironment.getEnvironmentId());
    }

    @Test
    @DisplayName("A GROUP-scoped request, once approved, becomes a GROUP grant")
    void groupScopedRequest_approvedToGroupGrant() {
        VmGroup g1 = createGroup("grp-req", 1);

        CreateAccessRequestDTO dto = new CreateAccessRequestDTO(AccessLevel.USER, "just the batch group", 14);
        dto.setScopeType(AccessScopeType.GROUP);
        dto.setGroupId(g1.getGroupId());

        EnvironmentAccessRequest request = accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(), requesterUser.getUserId(), dto);
        assertThat(request.getScopeType()).isEqualTo(AccessScopeType.GROUP);
        assertThat(request.getScopeId()).isEqualTo(g1.getGroupId());

        EnvironmentAccess grant = accessService.approveRequest(
                request.getRequestId(), adminUser.getUserId(), "ok", null);

        assertThat(grant.getScopeType()).isEqualTo(AccessScopeType.GROUP);
        assertThat(grant.getScopeId()).isEqualTo(g1.getGroupId());
        assertThat(grant.getInitiation()).isEqualTo(AccessInitiation.REQUEST);
        assertThat(grant.getSourceRequestId()).isEqualTo(request.getRequestId());
    }

    @Test
    @DisplayName("A GROUP request for a group outside the environment is rejected")
    void groupScopedRequest_wrongEnvironment_throws() {
        Environment other = new Environment();
        other.setEnvironmentId(UUID.randomUUID().toString());
        other.setName("other-env-req");
        other.setDisplayName("Other");
        other.setIsActive(true);
        other = environmentRepository.save(other);
        VmGroup foreign = new VmGroup();
        foreign.setGroupId(UUID.randomUUID().toString());
        foreign.setEnvironment(other);
        foreign.setName("foreign");
        foreign.setDisplayName("foreign");
        foreign.setSequencePosition(1);
        foreign = groupRepository.save(foreign);

        CreateAccessRequestDTO dto = new CreateAccessRequestDTO(AccessLevel.USER, "should fail here", null);
        dto.setScopeType(AccessScopeType.GROUP);
        dto.setGroupId(foreign.getGroupId());

        assertThatThrownBy(() -> accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(), requesterUser.getUserId(), dto))
                .isInstanceOf(ValidationException.class);
    }

    // ============= Access Request Tests =============

    @Test
    @DisplayName("Should create access request")
    void createAccessRequest_success() {
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO(
                AccessLevel.USER,
                "I need access to perform testing",
                30
        );

        EnvironmentAccessRequest request = accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(),
                dto
        );

        assertThat(request).isNotNull();
        assertThat(request.getRequestId()).isNotNull();
        assertThat(request.getStatus()).isEqualTo(AccessRequestStatus.PENDING);
        assertThat(request.getRequestedAccessLevel()).isEqualTo(AccessLevel.USER);
        assertThat(request.getBusinessJustification()).isEqualTo("I need access to perform testing");
        assertThat(request.getDurationDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("Should prevent duplicate pending requests")
    void createAccessRequest_duplicatePending_fails() {
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO(
                AccessLevel.USER,
                "First request for access",
                null
        );

        // First request should succeed
        accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(),
                dto
        );

        // Second request should fail
        assertThatThrownBy(() -> accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(),
                dto
        ))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("pending access request");
    }

    @Test
    @DisplayName("Should prevent request if user already has access")
    void createAccessRequest_alreadyHasAccess_fails() {
        // Grant access first
        GrantAccessDTO grantDto = new GrantAccessDTO(
                requesterUser.getEmail(),
                AccessLevel.VIEWER,
                null,
                null
        );
        accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(), grantDto);

        // Now try to request access
        CreateAccessRequestDTO requestDto = new CreateAccessRequestDTO(
                AccessLevel.USER,
                "I want higher access",
                null
        );

        assertThatThrownBy(() -> accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(),
                requestDto
        ))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already have active access");
    }

    @Test
    @DisplayName("A grant expiring inside the extension window can be extended by request")
    void createAccessRequest_extensionInsideWindow_extendsOnApproval() {
        accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(),
                new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.USER, 3, null));

        EnvironmentAccessRequest request = accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(), requesterUser.getUserId(),
                new CreateAccessRequestDTO(AccessLevel.USER, "Extension: still testing", 30));
        EnvironmentAccess extended = accessService.approveRequest(
                request.getRequestId(), adminUser.getUserId(), null, null);

        // M19: the 30 days are added to the current expiry (3 days away), not to the approval time.
        long daysLeft = (extended.getExpiresAt().getTime() - System.currentTimeMillis()) / 86_400_000L;
        assertThat(daysLeft).isBetween(32L, 33L);
        assertThat(accessService.getAccessForUser(requesterUser.getUserId())).hasSize(1);
    }

    @Test
    @DisplayName("An extension request must say how many days to extend by")
    void createAccessRequest_extensionWithoutDuration_fails() {
        accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(),
                new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.USER, 3, null));

        assertThatThrownBy(() -> accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(), requesterUser.getUserId(),
                new CreateAccessRequestDTO(AccessLevel.USER, "Extension: still testing", null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("how many days");
    }

    @Test
    @DisplayName("A grant expiring outside the extension window cannot be re-requested")
    void createAccessRequest_extensionOutsideWindow_fails() {
        accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(),
                new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.USER, 30, null));

        assertThatThrownBy(() -> accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(), requesterUser.getUserId(),
                new CreateAccessRequestDTO(AccessLevel.USER, "Extension: still testing", 30)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("already have active access");
    }

    @Test
    @DisplayName("Ended access lists revoked grants, but not a scope the user holds again")
    void getEndedAccessForUser_listsRevoked_skipsReheldScope() {
        String envId = testEnvironment.getEnvironmentId();
        GrantAccessDTO grant = new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.VIEWER, null, null);
        accessService.grantAccess(envId, adminUser.getUserId(), grant);
        accessService.revokeAccess(envId, requesterUser.getUserId(), adminUser.getUserId());

        List<EnvironmentAccess> ended = accessService.getEndedAccessForUser(requesterUser.getUserId(), 30);
        assertThat(ended).hasSize(1);
        assertThat(ended.get(0).getStatus()).isEqualTo(AccessStatus.REVOKED);

        accessService.grantAccess(envId, adminUser.getUserId(), grant);
        assertThat(accessService.getEndedAccessForUser(requesterUser.getUserId(), 30)).isEmpty();
    }

    @Test
    @DisplayName("Should approve access request")
    void approveRequest_success() {
        // Create request
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO(
                AccessLevel.USER,
                "I need access for development",
                null
        );
        EnvironmentAccessRequest request = accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(),
                dto
        );

        // Approve request
        EnvironmentAccess access = accessService.approveRequest(
                request.getRequestId(),
                adminUser.getUserId(),
                "Approved for development work",
                null
        );

        assertThat(access).isNotNull();
        assertThat(access.getAccessLevel()).isEqualTo(AccessLevel.USER);
        assertThat(access.getStatus()).isEqualTo(AccessStatus.ACTIVE);

        // Verify request status updated
        EnvironmentAccessRequest updatedRequest = accessService.getAccessRequest(request.getRequestId());
        assertThat(updatedRequest.getStatus()).isEqualTo(AccessRequestStatus.APPROVED);
    }

    @Test
    @DisplayName("Approving a request produces a REQUEST-initiated grant linked to the request")
    void approveRequest_grantIsRequestInitiated() {
        EnvironmentAccessRequest request = accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(), requesterUser.getUserId(),
                new CreateAccessRequestDTO(AccessLevel.USER, "dev work", 14));

        EnvironmentAccess access = accessService.approveRequest(
                request.getRequestId(), adminUser.getUserId(), "ok", null);

        assertThat(access.getInitiation()).isEqualTo(AccessInitiation.REQUEST);
        assertThat(access.getSourceRequestId()).isEqualTo(request.getRequestId());
        assertThat(access.getScopeType()).isEqualTo(AccessScopeType.ENVIRONMENT);
        assertThat(access.getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("Should deny access request")
    void denyRequest_success() {
        // Create request
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO(
                AccessLevel.ADMIN,
                "I want admin access",
                null
        );
        EnvironmentAccessRequest request = accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(),
                dto
        );

        // Deny request
        EnvironmentAccessRequest denied = accessService.denyRequest(
                request.getRequestId(),
                adminUser.getUserId(),
                "Admin access not justified"
        );

        assertThat(denied.getStatus()).isEqualTo(AccessRequestStatus.DENIED);
        assertThat(denied.getReviewDecisionNotes()).isEqualTo("Admin access not justified");
    }

    @Test
    @DisplayName("Should cancel own request")
    void cancelRequest_success() {
        // Create request
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO(
                AccessLevel.USER,
                "I need access",
                null
        );
        EnvironmentAccessRequest request = accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(),
                dto
        );

        // Cancel request
        EnvironmentAccessRequest cancelled = accessService.cancelRequest(
                request.getRequestId(),
                requesterUser.getUserId()
        );

        assertThat(cancelled.getStatus()).isEqualTo(AccessRequestStatus.CANCELLED);
    }

    @Test
    @DisplayName("Should prevent cancelling other's request")
    void cancelRequest_otherUser_fails() {
        // Create request
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO(
                AccessLevel.USER,
                "I need access",
                null
        );
        EnvironmentAccessRequest request = accessService.createAccessRequest(
                testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(),
                dto
        );

        // Try to cancel as different user
        assertThatThrownBy(() -> accessService.cancelRequest(
                request.getRequestId(),
                adminUser.getUserId()
        ))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("only cancel your own");
    }

    // ============= Direct Access Grant Tests =============

    @Test
    @DisplayName("Should grant access directly")
    void grantAccess_success() {
        GrantAccessDTO dto = new GrantAccessDTO(
                requesterUser.getEmail(),
                AccessLevel.USER,
                60,
                "Granted for project work"
        );

        EnvironmentAccess access = accessService.grantAccess(
                testEnvironment.getEnvironmentId(),
                adminUser.getUserId(),
                dto
        );

        assertThat(access).isNotNull();
        assertThat(access.getAccessLevel()).isEqualTo(AccessLevel.USER);
        assertThat(access.getStatus()).isEqualTo(AccessStatus.ACTIVE);
        assertThat(access.getExpiresAt()).isNotNull();
        assertThat(access.getNotes()).isEqualTo("Granted for project work");
    }

    @Test
    @DisplayName("Should update existing access when granting again")
    void grantAccess_updateExisting() {
        // Grant initial access
        GrantAccessDTO dto1 = new GrantAccessDTO(
                requesterUser.getEmail(),
                AccessLevel.VIEWER,
                null,
                null
        );
        accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(), dto1);

        // Grant higher access
        GrantAccessDTO dto2 = new GrantAccessDTO(
                requesterUser.getEmail(),
                AccessLevel.USER,
                null,
                "Upgraded access"
        );
        EnvironmentAccess updated = accessService.grantAccess(
                testEnvironment.getEnvironmentId(),
                adminUser.getUserId(),
                dto2
        );

        assertThat(updated.getAccessLevel()).isEqualTo(AccessLevel.USER);

        // Should still only have one access record
        List<EnvironmentAccess> accessList = accessService.getAccessForUser(requesterUser.getUserId());
        assertThat(accessList).hasSize(1);
    }

    // ============= Scoped grants (step 4) =============

    private VmGroup createGroup(String name, int seq) {
        VmGroup g = new VmGroup();
        g.setGroupId(UUID.randomUUID().toString());
        g.setEnvironment(testEnvironment);
        g.setName(name);
        g.setDisplayName(name);
        g.setSequencePosition(seq);
        return groupRepository.save(g);
    }

    private AccessGrantRequestDTO groupGrant(AccessLevel level, List<String> groupIds) {
        AccessGrantRequestDTO dto = new AccessGrantRequestDTO();
        dto.setUserEmail(requesterUser.getEmail());
        dto.setEnvironmentId(testEnvironment.getEnvironmentId());
        dto.setAccessLevel(level);
        dto.setScopeType(AccessScopeType.GROUP);
        dto.setGroupIds(groupIds);
        return dto;
    }

    @Test
    @DisplayName("grantScoped GROUP creates one active grant per group")
    void grantScoped_group_createsOneRowPerGroup() {
        VmGroup g1 = createGroup("grp-a", 1);
        VmGroup g2 = createGroup("grp-b", 2);

        List<EnvironmentAccess> grants = accessService.grantScoped(adminUser.getUserId(),
                groupGrant(AccessLevel.USER, List.of(g1.getGroupId(), g2.getGroupId())));

        assertThat(grants).hasSize(2);
        assertThat(grants).allSatisfy(ea -> {
            assertThat(ea.getScopeType()).isEqualTo(AccessScopeType.GROUP);
            assertThat(ea.getInitiation()).isEqualTo(AccessInitiation.DIRECT);
            assertThat(ea.getAccessLevel()).isEqualTo(AccessLevel.USER);
            assertThat(ea.getStatus()).isEqualTo(AccessStatus.ACTIVE);
        });
        assertThat(grants).extracting(EnvironmentAccess::getScopeId)
                .containsExactlyInAnyOrder(g1.getGroupId(), g2.getGroupId());

        Timestamp now = new Timestamp(System.currentTimeMillis());
        assertThat(accessService.getActiveGroupGrantLevels(requesterUser.getUserId(),
                List.of(g1.getGroupId(), g2.getGroupId()))).hasSize(2);
        // A group grant is NOT environment access.
        assertThat(accessRepository.hasAccess(testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(), now)).isFalse();
    }

    @Test
    @DisplayName("grantScoped GROUP rejects a group from another environment")
    void grantScoped_group_wrongEnvironment_throws() {
        Environment other = new Environment();
        other.setEnvironmentId(UUID.randomUUID().toString());
        other.setName("other-env");
        other.setDisplayName("Other");
        other.setIsActive(true);
        other = environmentRepository.save(other);

        VmGroup foreign = new VmGroup();
        foreign.setGroupId(UUID.randomUUID().toString());
        foreign.setEnvironment(other);
        foreign.setName("foreign");
        foreign.setDisplayName("foreign");
        foreign.setSequencePosition(1);
        foreign = groupRepository.save(foreign);

        AccessGrantRequestDTO dto = groupGrant(AccessLevel.USER, List.of(foreign.getGroupId()));
        assertThatThrownBy(() -> accessService.grantScoped(adminUser.getUserId(), dto))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    @DisplayName("grantScoped GROUP is rejected when group scope is disabled")
    void grantScoped_group_disabled_throws() {
        VmGroup g1 = createGroup("grp-a", 1);
        AccessGrantRequestDTO dto = groupGrant(AccessLevel.USER, List.of(g1.getGroupId()));

        ReflectionTestUtils.setField(accessService, "groupScopeEnabled", false);
        try {
            assertThatThrownBy(() -> accessService.grantScoped(adminUser.getUserId(), dto))
                    .isInstanceOf(ResourceNotFoundException.class);
        } finally {
            ReflectionTestUtils.setField(accessService, "groupScopeEnabled", true);
        }
    }

    @Test
    @DisplayName("updateGrant changes level in place, keeping scope")
    void updateGrant_changesLevelInPlace() {
        EnvironmentAccess granted = accessService.grantAccess(testEnvironment.getEnvironmentId(),
                adminUser.getUserId(), new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.USER, null, null));

        UpdateAccessGrantDTO patch = new UpdateAccessGrantDTO();
        patch.setAccessLevel(AccessLevel.ADMIN);
        EnvironmentAccess updated = accessService.updateGrant(adminUser.getUserId(), granted.getAccessId(), patch);

        assertThat(updated.getAccessId()).isEqualTo(granted.getAccessId());
        assertThat(updated.getAccessLevel()).isEqualTo(AccessLevel.ADMIN);
        assertThat(updated.getScopeType()).isEqualTo(AccessScopeType.ENVIRONMENT);
        assertThat(accessService.getAccessForUser(requesterUser.getUserId())).hasSize(1);
    }

    @Test
    @DisplayName("revokeGrantById marks the grant revoked")
    void revokeGrantById_marksRevoked() {
        VmGroup g1 = createGroup("grp-a", 1);
        List<EnvironmentAccess> grants = accessService.grantScoped(adminUser.getUserId(),
                groupGrant(AccessLevel.USER, List.of(g1.getGroupId())));
        String accessId = grants.get(0).getAccessId();

        accessService.revokeGrantById(adminUser.getUserId(), accessId);

        assertThat(accessService.getGrantById(accessId).getStatus()).isEqualTo(AccessStatus.REVOKED);
    }

    @Test
    @DisplayName("getGrantById throws for an unknown id")
    void getGrantById_unknown_throws() {
        assertThatThrownBy(() -> accessService.getGrantById("no-such-id"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("findDistinctActiveEnvironmentsForUser collapses several grants in one environment")
    void distinctEnvironmentsForUser_dedupesAcrossScopes() {
        VmGroup g1 = createGroup("grp-x", 1);
        accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(),
                new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.VIEWER, null, null));
        accessService.grantScoped(adminUser.getUserId(),
                groupGrant(AccessLevel.USER, List.of(g1.getGroupId())));

        Page<Environment> envs = accessRepository.findDistinctActiveEnvironmentsForUser(
                requesterUser.getUserId(), null, new Timestamp(System.currentTimeMillis()), PageRequest.of(0, 10));

        assertThat(envs.getTotalElements()).isEqualTo(1);
        assertThat(envs.getContent()).extracting(Environment::getEnvironmentId)
                .containsExactly(testEnvironment.getEnvironmentId());
    }

    @Test
    @DisplayName("Re-granting with clearExpiry drops the expiry of a time-boxed grant")
    void grantAccess_clearExpiry_makesPermanent() {
        accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(),
                new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.USER, 30, "time-boxed"));

        GrantAccessDTO permanent = new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.USER, null, "made permanent");
        permanent.setClearExpiry(true);
        EnvironmentAccess updated = accessService.grantAccess(
                testEnvironment.getEnvironmentId(), adminUser.getUserId(), permanent);

        assertThat(updated.getExpiresAt()).isNull();
        assertThat(accessService.getAccessForUser(requesterUser.getUserId())).hasSize(1);
    }

    @Test
    @DisplayName("Should revoke access")
    void revokeAccess_success() {
        // Grant access first
        GrantAccessDTO dto = new GrantAccessDTO(
                requesterUser.getEmail(),
                AccessLevel.USER,
                null,
                null
        );
        accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(), dto);

        // Verify access exists
        assertThat(accessService.hasAccess(testEnvironment.getEnvironmentId(), requesterUser.getUserId())).isTrue();

        // Revoke access
        accessService.revokeAccess(
                testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(),
                adminUser.getUserId()
        );

        // Verify access revoked
        assertThat(accessService.hasAccess(testEnvironment.getEnvironmentId(), requesterUser.getUserId())).isFalse();
    }

    @Test
    @DisplayName("Should fail to revoke non-existent access")
    void revokeAccess_noAccess_fails() {
        assertThatThrownBy(() -> accessService.revokeAccess(
                testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(),
                adminUser.getUserId()
        ))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not have active access");
    }

    // ============= Access Query Tests =============

    @Test
    @DisplayName("Should check access level")
    void hasAccessLevel_success() {
        // Grant USER access
        GrantAccessDTO dto = new GrantAccessDTO(
                requesterUser.getEmail(),
                AccessLevel.USER,
                null,
                null
        );
        accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(), dto);

        // Should have USER level
        assertThat(accessService.hasAccessLevel(
                testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(),
                AccessLevel.USER
        )).isTrue();

        // Should have VIEWER level (lower)
        assertThat(accessService.hasAccessLevel(
                testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(),
                AccessLevel.VIEWER
        )).isTrue();

        // Should NOT have ADMIN level (higher)
        assertThat(accessService.hasAccessLevel(
                testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(),
                AccessLevel.ADMIN
        )).isFalse();
    }

    @Test
    @DisplayName("Should return only environments where user holds ADMIN-level access")
    void getAdministeredEnvironmentIds_returnsOnlyAdminLevelEnvironments() {
        Environment secondEnvironment = new Environment();
        secondEnvironment.setEnvironmentId(UUID.randomUUID().toString());
        secondEnvironment.setName("test-env-2");
        secondEnvironment.setDisplayName("Test Environment 2");
        secondEnvironment.setIsActive(true);
        secondEnvironment = environmentRepository.save(secondEnvironment);

        // ADMIN-level access on testEnvironment, only VIEWER-level on secondEnvironment
        GrantAccessDTO adminGrant = new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.ADMIN, null, null);
        GrantAccessDTO viewerGrant = new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.VIEWER, null, null);
        accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(), adminGrant);
        accessService.grantAccess(secondEnvironment.getEnvironmentId(), adminUser.getUserId(), viewerGrant);

        List<String> administered = accessService.getAdministeredEnvironmentIds(requesterUser.getUserId());

        assertThat(administered).containsExactly(testEnvironment.getEnvironmentId());
    }

    @Test
    @DisplayName("Should get access for environment")
    void getAccessForEnvironment_success() {
        // Grant access to multiple users
        User anotherUser = User.fromAzureAd("another-oid", "another@example.com", "Another User");
        anotherUser = userRepository.save(anotherUser);

        GrantAccessDTO dto1 = new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.USER, null, null);
        GrantAccessDTO dto2 = new GrantAccessDTO(anotherUser.getEmail(), AccessLevel.VIEWER, null, null);

        accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(), dto1);
        accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(), dto2);

        List<EnvironmentAccess> accessList = accessService.getAccessForEnvironment(testEnvironment.getEnvironmentId());

        assertThat(accessList).hasSize(2);
    }

    @Test
    @DisplayName("Should get pending requests")
    void getPendingRequests_success() {
        // Create multiple requests
        User anotherUser = User.fromAzureAd("another-oid", "another@example.com", "Another User");
        anotherUser = userRepository.save(anotherUser);

        CreateAccessRequestDTO dto = new CreateAccessRequestDTO(AccessLevel.USER, "Need access please", null);

        accessService.createAccessRequest(testEnvironment.getEnvironmentId(), requesterUser.getUserId(), dto);
        accessService.createAccessRequest(testEnvironment.getEnvironmentId(), anotherUser.getUserId(), dto);

        List<EnvironmentAccessRequest> pending = accessService.getPendingRequests();

        assertThat(pending).hasSize(2);
        assertThat(pending).allMatch(r -> r.getStatus() == AccessRequestStatus.PENDING);
    }

    @Test
    @DisplayName("C4: revoke, re-grant and revoke again the same user on the same environment")
    void revokeRegrantRevoke_keepsBothRevokedRows() {
        String envId = testEnvironment.getEnvironmentId();
        GrantAccessDTO dto = new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.USER, null, "c4");

        accessService.grantAccess(envId, adminUser.getUserId(), dto);
        accessService.revokeAccess(envId, requesterUser.getUserId(), adminUser.getUserId());
        accessService.grantAccess(envId, adminUser.getUserId(), dto);
        accessService.revokeAccess(envId, requesterUser.getUserId(), adminUser.getUserId());
        accessRepository.flush();

        assertThat(accessRepository.findAll())
                .filteredOn(ea -> ea.getUser().getUserId().equals(requesterUser.getUserId()))
                .extracting(EnvironmentAccess::getStatus)
                .containsExactly(AccessStatus.REVOKED, AccessStatus.REVOKED);
    }

    @Test
    @DisplayName("C4: an environment grant and a group grant in the same environment are both active")
    void environmentAndGroupGrantInOneEnvironment_bothActive() {
        VmGroup g1 = createGroup("grp-c4", 1);
        accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(),
                new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.VIEWER, null, null));
        accessService.grantScoped(adminUser.getUserId(), groupGrant(AccessLevel.USER, List.of(g1.getGroupId())));
        accessRepository.flush();

        assertThat(accessRepository.findAll())
                .filteredOn(ea -> ea.getUser().getUserId().equals(requesterUser.getUserId()))
                .extracting(EnvironmentAccess::getStatus)
                .containsExactly(AccessStatus.ACTIVE, AccessStatus.ACTIVE);
    }

    @ParameterizedTest(name = "held {0}, required {1}")
    @CsvSource({
            "VIEWER, VIEWER, true", "VIEWER, USER, false", "VIEWER, ADMIN, false",
            "USER, VIEWER, true", "USER, USER, true", "USER, ADMIN, false",
            "ADMIN, VIEWER, true", "ADMIN, USER, true", "ADMIN, ADMIN, true"})
    @DisplayName("H6: repository level queries compare by rank, not alphabetically")
    void repositoryLevelQueries_compareByRank(AccessLevel held, AccessLevel required, boolean expected) {
        String envId = testEnvironment.getEnvironmentId();
        accessService.grantAccess(envId, adminUser.getUserId(),
                new GrantAccessDTO(requesterUser.getEmail(), held, null, null));
        Timestamp now = new Timestamp(System.currentTimeMillis());

        assertThat(accessRepository.hasAccessLevel(envId, requesterUser.getUserId(), required, now))
                .isEqualTo(expected);
        assertThat(accessRepository.findByUserWithMinAccessLevel(requesterUser.getUserId(), required, now))
                .extracting(ea -> ea.getEnvironment().getEnvironmentId())
                .isEqualTo(expected ? List.of(envId) : List.of());
    }

    @Test
    @DisplayName("M18: the configured maxima are inclusive (request 180 days, grant 365 days)")
    void durationMaxima_areInclusive() {
        CreateAccessRequestDTO request = new CreateAccessRequestDTO();
        request.setAccessLevel(AccessLevel.USER);
        request.setBusinessJustification("Need access for the release");
        request.setDurationDays(180);
        assertThat(accessService.createAccessRequest(testEnvironment.getEnvironmentId(),
                requesterUser.getUserId(), request).getDurationDays()).isEqualTo(180);

        EnvironmentAccess granted = accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(),
                new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.USER, 365, null));
        assertThat(granted.getExpiresAt()).isNotNull();

        assertThatThrownBy(() -> accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(),
                new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.USER, 366, null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Access can be granted for at most 365 days");
    }

    // ============= Extensions (E04-T05, M19) =============

    /** requesterUser holds USER on testEnvironment, expiring at now + {@code expiresIn}. */
    private EnvironmentAccess grantExpiringIn(java.time.Duration expiresIn) {
        String accessId = accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(),
                new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.USER, 30, null)).getAccessId();
        // Reload from MySQL: the instance created in this transaction lacks the DB-generated grantedAt.
        entityManager.flush();
        entityManager.clear();
        EnvironmentAccess grant = accessRepository.findById(accessId).orElseThrow();
        grant.setExpiresAt(Timestamp.from(java.time.Instant.now().plus(expiresIn)));
        return accessRepository.saveAndFlush(grant);
    }

    private EnvironmentAccessRequest requestDays(Integer days) {
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO();
        dto.setAccessLevel(AccessLevel.USER);
        dto.setBusinessJustification("Still working on the release");
        dto.setDurationDays(days);
        return accessService.createAccessRequest(testEnvironment.getEnvironmentId(), requesterUser.getUserId(), dto);
    }

    private static void assertAbout(Timestamp actual, java.time.Instant expected) {
        assertThat(java.time.Duration.between(actual.toInstant(), expected).abs())
                .isLessThan(java.time.Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("M19: a request while holding an expiring grant is flagged as an extension; a first request is not")
    void createAccessRequest_flagsExtensions() {
        assertThat(requestDays(null).isExtension()).isFalse();
        requestRepository.deleteAll();

        grantExpiringIn(java.time.Duration.ofDays(3));
        EnvironmentAccessRequest extension = requestDays(7);

        assertThat(extension.isExtension()).isTrue();
        assertThat(requestRepository.findById(extension.getRequestId()).orElseThrow().isExtension()).isTrue();
    }

    @Test
    @DisplayName("M19: approving an extension adds the days to the current expiry, not to the approval time")
    void approveExtension_addsToTheCurrentExpiry() {
        EnvironmentAccess grant = grantExpiringIn(java.time.Duration.ofDays(6));
        java.time.Instant currentExpiry = grant.getExpiresAt().toInstant();
        EnvironmentAccessRequest extension = requestDays(7);

        EnvironmentAccess extended = accessService.approveRequest(extension.getRequestId(), adminUser.getUserId(), null, null);

        assertThat(extended.getAccessId()).isEqualTo(grant.getAccessId());
        assertAbout(extended.getExpiresAt(), currentExpiry.plus(7, java.time.temporal.ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("M19: an extension approved after the grant lapsed counts from the approval time")
    void approveExtension_afterLapse_countsFromNow() {
        EnvironmentAccess grant = grantExpiringIn(java.time.Duration.ofDays(2));
        EnvironmentAccessRequest extension = requestDays(7);
        grant.setExpiresAt(Timestamp.from(java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS)));
        accessRepository.saveAndFlush(grant); // lapsed, not yet flipped to EXPIRED by the job

        EnvironmentAccess extended = accessService.approveRequest(extension.getRequestId(), adminUser.getUserId(), null, null);

        assertAbout(extended.getExpiresAt(), java.time.Instant.now().plus(7, java.time.temporal.ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("M19: a reviewer's duration override on an extension is also added to the current expiry")
    void approveExtension_reviewerOverride_addsToTheCurrentExpiry() {
        EnvironmentAccess grant = grantExpiringIn(java.time.Duration.ofDays(4));
        java.time.Instant currentExpiry = grant.getExpiresAt().toInstant();
        EnvironmentAccessRequest extension = requestDays(30);

        EnvironmentAccess extended = accessService.approveRequest(extension.getRequestId(), adminUser.getUserId(), null, 14);

        assertAbout(extended.getExpiresAt(), currentExpiry.plus(14, java.time.temporal.ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("M19: a first (non-extension) request approved for 30 days expires 30 days after approval")
    void approveNewRequest_countsFromNow() {
        EnvironmentAccessRequest request = requestDays(30);

        EnvironmentAccess granted = accessService.approveRequest(request.getRequestId(), adminUser.getUserId(), null, null);

        assertAbout(granted.getExpiresAt(), java.time.Instant.now().plus(30, java.time.temporal.ChronoUnit.DAYS));
    }

    @Test
    @DisplayName("M19: a direct admin grant with a duration still counts from today")
    void directGrantOnExistingGrant_countsFromNow() {
        grantExpiringIn(java.time.Duration.ofDays(5));

        EnvironmentAccess regranted = accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(),
                new GrantAccessDTO(requesterUser.getEmail(), AccessLevel.USER, 10, null));

        assertAbout(regranted.getExpiresAt(), java.time.Instant.now().plus(10, java.time.temporal.ChronoUnit.DAYS));
    }
}
