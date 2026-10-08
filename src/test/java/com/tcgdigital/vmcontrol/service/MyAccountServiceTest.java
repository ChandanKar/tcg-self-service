package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.dto.EnvironmentAccessDTO;
import com.tcgdigital.vmcontrol.dto.GrantAccessDTO;
import com.tcgdigital.vmcontrol.dto.MyActivityItemDTO;
import com.tcgdigital.vmcontrol.dto.MyProfileDTO;
import com.tcgdigital.vmcontrol.dto.UpdateAccessGrantDTO;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Integration tests for MyAccountService (My Account panel data).
 */
@Transactional
class MyAccountServiceTest extends AbstractIntegrationTest {

    @Autowired
    private MyAccountService myAccountService;

    @Autowired
    private EnvironmentAccessService accessService;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OperationExecutionRepository executionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Environment environment;
    private User user;
    private User admin;

    @BeforeEach
    void setUp() {
        environment = new Environment();
        environment.setEnvironmentId(UUID.randomUUID().toString());
        environment.setName("my-account-env-" + UUID.randomUUID());
        environment.setDisplayName("My Account Env");
        environment.setIsActive(true);
        environment = environmentRepository.save(environment);

        String suffix = UUID.randomUUID().toString();
        admin = User.fromAzureAd("admin-" + suffix, "admin-" + suffix + "@example.com", "Admin User");
        admin.setAdmin(true);
        admin = userRepository.save(admin);

        user = User.fromAzureAd("user-" + suffix, "user-" + suffix + "@example.com", "Plain User");
        user.setOnboardedBy(admin.getUserId());
        user = userRepository.save(user);
    }

    @Test
    @DisplayName("Profile shows the sign-in before the current one and who onboarded the user")
    void profile_previousLoginAndOnboardedBy() {
        user.recordLogin();
        Timestamp first = user.getLastLoginAt();
        user.recordLogin();
        userRepository.save(user);

        MyProfileDTO profile = myAccountService.getProfile(user.getUserId());

        assertThat(profile.previousLoginAt()).isEqualTo(first);
        assertThat(profile.onboardedByName()).isEqualTo("Admin User");
        assertThat(profile.authMethod()).isEqualTo("LOCAL");
        assertThat(profile.administeredEnvironments()).isEmpty();
        assertThat(profile.extensionWindowDays()).isEqualTo(accessService.getExtensionWindowDays());
    }

    @Test
    @DisplayName("Profile lists environments the user administers")
    void profile_listsAdministeredEnvironments() {
        accessService.grantAccess(environment.getEnvironmentId(), admin.getUserId(),
                new GrantAccessDTO(user.getEmail(), AccessLevel.ADMIN, null, null));

        MyProfileDTO profile = myAccountService.getProfile(user.getUserId());

        assertThat(profile.administeredEnvironments())
                .extracting(MyProfileDTO.EnvironmentRef::name)
                .containsExactly("My Account Env");
    }

    @Test
    @DisplayName("H6: environments where the user is only VIEWER or USER are not listed as administered")
    void profile_doesNotListViewerOrUserEnvironmentsAsAdministered() {
        Environment other = new Environment();
        other.setEnvironmentId(UUID.randomUUID().toString());
        other.setName("my-account-env-" + UUID.randomUUID());
        other.setDisplayName("Viewer Env");
        other.setIsActive(true);
        other = environmentRepository.save(other);
        accessService.grantAccess(environment.getEnvironmentId(), admin.getUserId(),
                new GrantAccessDTO(user.getEmail(), AccessLevel.USER, null, null));
        accessService.grantAccess(other.getEnvironmentId(), admin.getUserId(),
                new GrantAccessDTO(user.getEmail(), AccessLevel.VIEWER, null, null));

        MyProfileDTO profile = myAccountService.getProfile(user.getUserId());

        assertThat(profile.administeredEnvironments()).isEmpty();
    }

    @Test
    @DisplayName("Activity merges operations, requests, decisions and direct grants, newest first")
    void activity_mergesSourcesNewestFirst() {
        accessService.grantAccess(environment.getEnvironmentId(), admin.getUserId(),
                new GrantAccessDTO(user.getEmail(), AccessLevel.VIEWER, null, null));

        Environment other = new Environment();
        other.setEnvironmentId(UUID.randomUUID().toString());
        other.setName("my-account-other-" + UUID.randomUUID());
        other.setDisplayName("Other Env");
        other.setIsActive(true);
        other = environmentRepository.save(other);
        EnvironmentAccessRequest request = accessService.createAccessRequest(other.getEnvironmentId(),
                user.getUserId(), new CreateAccessRequestDTO(AccessLevel.USER, "need to run tests", 7));
        accessService.denyRequest(request.getRequestId(), admin.getUserId(), "not this sprint");

        OperationExecution op = new OperationExecution();
        op.setExecutionId(UUID.randomUUID().toString());
        op.setEnvironment(environment);
        op.setOperationType(OperationType.START);
        op.setStatus(ExecutionStatus.PARTIAL_SUCCESS);
        op.setInitiatedByUserId(user.getUserId());
        op.setTotalTargets(4);
        op.setCompletedTargets(3);
        op.setFailedTargets(1);
        executionRepository.save(op);

        List<MyActivityItemDTO> activity = myAccountService.getActivity(user.getUserId(), 25);

        assertThat(activity).extracting(MyActivityItemDTO::kind, MyActivityItemDTO::event)
                .contains(
                        tuple("OPERATION", "START"),
                        tuple("ACCESS", "GRANTED"),
                        tuple("ACCESS", "REQUESTED"),
                        tuple("ACCESS", "DENIED"));
        assertThat(activity).isSortedAccordingTo(
                Comparator.comparing(MyActivityItemDTO::occurredAt).reversed());

        MyActivityItemDTO denied = activity.stream().filter(i -> "DENIED".equals(i.event())).findFirst().orElseThrow();
        assertThat(denied.actorName()).isEqualTo("Admin User");
        assertThat(denied.note()).isEqualTo("not this sprint");
        assertThat(denied.environmentName()).isEqualTo("Other Env");

        MyActivityItemDTO started = activity.stream().filter(i -> "OPERATION".equals(i.kind())).findFirst().orElseThrow();
        assertThat(started.status()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(started.failedTargets()).isEqualTo(1);
    }

    @Test
    @DisplayName("Activity respects the limit")
    void activity_respectsLimit() {
        for (int i = 0; i < 3; i++) {
            OperationExecution op = new OperationExecution();
            op.setExecutionId(UUID.randomUUID().toString());
            op.setEnvironment(environment);
            op.setOperationType(OperationType.STOP);
            op.setStatus(ExecutionStatus.COMPLETED);
            op.setInitiatedByUserId(user.getUserId());
            executionRepository.save(op);
        }

        assertThat(myAccountService.getActivity(user.getUserId(), 2)).hasSize(2);
    }

    // ============= Granter kept, edits and extensions shown (E04-T08) =============

    private User secondAdmin() {
        String suffix = UUID.randomUUID().toString();
        User carol = User.fromAzureAd("carol-" + suffix, "carol-" + suffix + "@example.com", "Carol Editor");
        carol.setAdmin(true);
        return userRepository.save(carol);
    }

    /** Persist pending changes and move a grant's grantedAt one hour back, so ordering is deterministic. */
    private void backdateGrant(EnvironmentAccess grant) {
        entityManager.flush();
        jdbcTemplate.update("UPDATE environment_access SET granted_at = ? WHERE access_id = ?",
                new Timestamp(System.currentTimeMillis() - 3_600_000L), grant.getAccessId());
        entityManager.clear();
    }

    @Test
    @DisplayName("LOW-ACC-3: a direct edit by another admin is an UPDATED event after the original GRANTED one")
    void activity_directEditIsUpdatedByTheEditor() {
        EnvironmentAccess grant = accessService.grantAccess(environment.getEnvironmentId(), admin.getUserId(),
                new GrantAccessDTO(user.getEmail(), AccessLevel.VIEWER, null, null));
        backdateGrant(grant);
        User carol = secondAdmin();
        UpdateAccessGrantDTO patch = new UpdateAccessGrantDTO();
        patch.setAccessLevel(AccessLevel.VIEWER);
        patch.setNotes("Moved to the payments team");
        accessService.updateGrant(carol.getUserId(), grant.getAccessId(), patch);
        entityManager.flush();
        entityManager.clear();

        List<MyActivityItemDTO> access = myAccountService.getActivity(user.getUserId(), 25).stream()
                .filter(i -> "ACCESS".equals(i.kind())).toList();

        assertThat(access).extracting(MyActivityItemDTO::event, MyActivityItemDTO::actorName)
                .containsExactly(tuple("UPDATED", "Carol Editor"), tuple("GRANTED", "Admin User"));
    }

    @Test
    @DisplayName("LOW-ACC-4: an approved extension request shows as EXTENDED by the reviewer")
    void activity_approvedExtensionIsExtended() {
        accessService.grantAccess(environment.getEnvironmentId(), admin.getUserId(),
                new GrantAccessDTO(user.getEmail(), AccessLevel.USER, 3, null));
        EnvironmentAccessRequest extension = accessService.createAccessRequest(environment.getEnvironmentId(),
                user.getUserId(), new CreateAccessRequestDTO(AccessLevel.USER, "Release testing runs longer", 7));
        accessService.approveRequest(extension.getRequestId(), admin.getUserId(), null, null);

        List<MyActivityItemDTO> activity = myAccountService.getActivity(user.getUserId(), 25);

        assertThat(activity).extracting(MyActivityItemDTO::event, MyActivityItemDTO::actorName)
                .contains(tuple("EXTENDED", "Admin User"))
                .doesNotContain(tuple("APPROVED", "Admin User"), tuple("UPDATED", "Admin User"));
    }

    @Test
    @DisplayName("LOW-ACC-3: the grant keeps the original granter and names the last editor")
    void grants_keepOriginalGranterAndNameTheEditor() {
        EnvironmentAccess grant = accessService.grantAccess(environment.getEnvironmentId(), admin.getUserId(),
                new GrantAccessDTO(user.getEmail(), AccessLevel.VIEWER, null, null));
        User carol = secondAdmin();
        UpdateAccessGrantDTO patch = new UpdateAccessGrantDTO();
        patch.setAccessLevel(AccessLevel.USER);
        accessService.updateGrant(carol.getUserId(), grant.getAccessId(), patch);
        entityManager.flush();
        entityManager.clear();

        EnvironmentAccessDTO dto = EnvironmentAccessDTO.fromEntity(accessService.getGrantById(grant.getAccessId()));

        assertThat(dto.getGrantedByUserName()).isEqualTo("Admin User");
        assertThat(dto.getLastModifiedByUserName()).isEqualTo("Carol Editor");
        assertThat(dto.getLastModifiedAt()).isNotNull();
    }
}
