package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.CreateAccessRequestDTO;
import com.tcgdigital.vmcontrol.dto.GrantAccessDTO;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRequestRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for EnvironmentAccessController.
 * Note: These tests run with security disabled, so user context is not available.
 * Tests use the service directly for mutations and verify via GET endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class EnvironmentAccessControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EnvironmentAccessRepository accessRepository;

    @Autowired
    private EnvironmentAccessRequestRepository requestRepository;

    @Autowired
    private EnvironmentAccessService accessService;

    private Environment testEnvironment;
    private User testUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        // Clean up
        requestRepository.deleteAll();
        accessRepository.deleteAll();
        userRepository.deleteAll();

        // Create test environment
        testEnvironment = environmentRepository.findByName("test-env").orElseGet(() -> {
            Environment env = new Environment();
            env.setEnvironmentId(UUID.randomUUID().toString());
            env.setName("test-env");
            env.setDisplayName("Test Environment");
            env.setIsActive(true);
            return environmentRepository.save(env);
        });

        // Create test users
        testUser = User.fromAzureAd("test-oid", "test@example.com", "Test User");
        testUser = userRepository.save(testUser);

        adminUser = User.fromAzureAd("admin-oid", "admin@example.com", "Admin User");
        adminUser.setAdmin(true);
        adminUser = userRepository.save(adminUser);

        // Set up security context so getCurrentUserId() resolves to testUser
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("test@example.com", null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ============= Access Grant Endpoint Tests =============

    @Test
    @DisplayName("Should list environment access")
    void listEnvironmentAccess_returnsAccess() throws Exception {
        // Grant access to user
        EnvironmentAccess access = EnvironmentAccess.create(testEnvironment, testUser, AccessLevel.USER, adminUser);
        accessRepository.save(access);

        mockMvc.perform(get("/api/v1/environments/{envId}/access", testEnvironment.getEnvironmentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].userEmail").value("test@example.com"))
                .andExpect(jsonPath("$[0].accessLevel").value("USER"));
    }

    @Test
    @DisplayName("Should grant access and verify via GET")
    void grantAccess_verifyViaGet() throws Exception {
        // Grant access via service
        GrantAccessDTO dto = new GrantAccessDTO(
                testUser.getEmail(),
                AccessLevel.USER,
                30,
                "Project access"
        );
        accessService.grantAccess(testEnvironment.getEnvironmentId(), adminUser.getUserId(), dto);

        // Verify via GET endpoint
        mockMvc.perform(get("/api/v1/environments/{envId}/access", testEnvironment.getEnvironmentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].accessLevel").value("USER"))
                .andExpect(jsonPath("$[0].userEmail").value("test@example.com"))
                .andExpect(jsonPath("$[0].notes").value("Project access"));
    }

    @Test
    @DisplayName("Should revoke access and verify via GET")
    void revokeAccess_verifyViaGet() throws Exception {
        // Grant access first
        EnvironmentAccess access = EnvironmentAccess.create(testEnvironment, testUser, AccessLevel.USER, adminUser);
        accessRepository.save(access);

        // Revoke via service
        accessService.revokeAccess(testEnvironment.getEnvironmentId(), testUser.getUserId(), adminUser.getUserId());

        // Verify via GET endpoint - should be empty
        mockMvc.perform(get("/api/v1/environments/{envId}/access", testEnvironment.getEnvironmentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ============= Access Request Endpoint Tests =============

    @Test
    @DisplayName("Should create access request and verify via GET")
    void createAccessRequest_verifyViaGet() throws Exception {
        // Create via service
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO(
                AccessLevel.USER,
                "I need access for testing purposes",
                null
        );
        accessService.createAccessRequest(testEnvironment.getEnvironmentId(), testUser.getUserId(), dto);

        // Verify via pending endpoint
        mockMvc.perform(get("/api/v1/access-requests/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].requestedAccessLevel").value("USER"))
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].businessJustification").value("I need access for testing purposes"));
    }

    @Test
    @DisplayName("Should create access request via POST endpoint")
    void createAccessRequest_postEndpoint_returnsCreated() throws Exception {
        CreateAccessRequestDTO dto = new CreateAccessRequestDTO(
                AccessLevel.USER,
                "I need access for testing purposes",
                null
        );

        mockMvc.perform(post("/api/v1/environments/{envId}/access-requests", testEnvironment.getEnvironmentId())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId", not(emptyString())))
                .andExpect(jsonPath("$.environmentId").value(testEnvironment.getEnvironmentId()))
                .andExpect(jsonPath("$.requesterId").value(testUser.getUserId()))
                .andExpect(jsonPath("$.requestedAccessLevel").value("USER"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.businessJustification").value("I need access for testing purposes"));
    }

    @Test
    @DisplayName("Should list pending requests")
    void listPendingRequests_returnsPending() throws Exception {
        // Create a pending request
        EnvironmentAccessRequest request = EnvironmentAccessRequest.create(
                testEnvironment,
                testUser,
                AccessLevel.USER,
                "Need access",
                null
        );
        requestRepository.save(request);

        mockMvc.perform(get("/api/v1/access-requests/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("Should get my requests")
    void getMyRequests_returnsUserRequests() throws Exception {
        // Create a request for the user
        EnvironmentAccessRequest request = EnvironmentAccessRequest.create(
                testEnvironment,
                testUser,
                AccessLevel.USER,
                "Need access",
                null
        );
        requestRepository.save(request);

        mockMvc.perform(get("/api/v1/access-requests/my"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Should approve request and verify access granted")
    void approveRequest_verifyAccessGranted() throws Exception {
        // Create a pending request
        EnvironmentAccessRequest request = EnvironmentAccessRequest.create(
                testEnvironment,
                testUser,
                AccessLevel.USER,
                "Need access for work",
                null
        );
        request = requestRepository.save(request);

        // Approve via service
        accessService.approveRequest(request.getRequestId(), adminUser.getUserId(), "Approved for project", null);

        // Verify access was granted via endpoint
        mockMvc.perform(get("/api/v1/environments/{envId}/access", testEnvironment.getEnvironmentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].accessLevel").value("USER"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("Should deny request and verify status")
    void denyRequest_verifyStatus() throws Exception {
        // Create a pending request
        EnvironmentAccessRequest request = EnvironmentAccessRequest.create(
                testEnvironment,
                testUser,
                AccessLevel.ADMIN,
                "I want admin access",
                null
        );
        request = requestRepository.save(request);

        // Deny via service
        accessService.denyRequest(request.getRequestId(), adminUser.getUserId(), "Admin access not justified");

        // Verify via endpoint
        mockMvc.perform(get("/api/v1/access-requests/{requestId}", request.getRequestId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DENIED"))
                .andExpect(jsonPath("$.reviewDecisionNotes").value("Admin access not justified"));
    }

    @Test
    @DisplayName("Should cancel request and verify status")
    void cancelRequest_verifyStatus() throws Exception {
        // Create a pending request
        EnvironmentAccessRequest request = EnvironmentAccessRequest.create(
                testEnvironment,
                testUser,
                AccessLevel.USER,
                "Need access",
                null
        );
        request = requestRepository.save(request);

        // Cancel via service
        accessService.cancelRequest(request.getRequestId(), testUser.getUserId());

        // Verify via endpoint
        mockMvc.perform(get("/api/v1/access-requests/{requestId}", request.getRequestId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("Should get access request details")
    void getAccessRequest_success() throws Exception {
        // Create a request
        EnvironmentAccessRequest request = EnvironmentAccessRequest.create(
                testEnvironment,
                testUser,
                AccessLevel.USER,
                "Need access for testing",
                30
        );
        request = requestRepository.save(request);

        mockMvc.perform(get("/api/v1/access-requests/{requestId}", request.getRequestId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(request.getRequestId()))
                .andExpect(jsonPath("$.requestedAccessLevel").value("USER"))
                .andExpect(jsonPath("$.durationDays").value(30));
    }

    @Test
    @DisplayName("Should list environment access requests")
    void listEnvironmentAccessRequests_returnsRequests() throws Exception {
        // Create a request
        EnvironmentAccessRequest request = EnvironmentAccessRequest.create(
                testEnvironment, testUser, AccessLevel.USER, "Request 1", null);
        requestRepository.save(request);

        mockMvc.perform(get("/api/v1/environments/{envId}/access-requests", testEnvironment.getEnvironmentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    @DisplayName("Should return 404 for non-existent request")
    void getAccessRequest_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/access-requests/{requestId}", "non-existent-id"))
                .andExpect(status().isNotFound());
    }
}

