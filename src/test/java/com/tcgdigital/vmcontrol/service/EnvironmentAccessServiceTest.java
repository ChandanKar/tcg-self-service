package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.dto.GrantAccessDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRequestRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for EnvironmentAccessService.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EnvironmentAccessServiceTest {

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

    private Environment testEnvironment;
    private User requesterUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        // Clean up
        requestRepository.deleteAll();
        accessRepository.deleteAll();
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
}

