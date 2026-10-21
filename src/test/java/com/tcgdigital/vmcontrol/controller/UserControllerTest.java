package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.UpdateUserRoleDTO;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for UserController.
 * Note: These tests run with security disabled (entraid.enabled=false in test profile),
 * so authorization checks are bypassed. Authorization is tested via the @PreAuthorize
 * annotations which are enforced when security is enabled.
 */
@AutoConfigureMockMvc
@Transactional
class UserControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    private User testUser;
    private User adminUser;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        // Create a regular test user
        testUser = User.fromAzureAd("test-oid-123", "test@example.com", "Test User");
        testUser = userRepository.save(testUser);

        // Create an admin user
        adminUser = User.fromAzureAd("admin-oid-456", "admin@example.com", "Admin User");
        adminUser.setAdmin(true);
        adminUser = userRepository.save(adminUser);

        // Set up security context so getCurrentUserId() resolves to adminUser
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin@example.com", null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Should list all users")
    void listUsers_returnsUsers() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].email", containsInAnyOrder("test@example.com", "admin@example.com")))
                // U05-T05: how each person signs in
                .andExpect(jsonPath("$[*].signInMethod", containsInAnyOrder("MICROSOFT", "MICROSOFT")))
                .andExpect(jsonPath("$[*].signsInWithMicrosoft", containsInAnyOrder(true, true)));
    }

    @Test
    @DisplayName("Should get user by ID")
    void getUserById_returnsUser() throws Exception {
        mockMvc.perform(get("/api/v1/users/{userId}", testUser.getUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(testUser.getUserId()))
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.displayName").value("Test User"));
    }

    @Test
    @DisplayName("Should return 404 for non-existent user")
    void getUserById_nonExistent_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/users/{userId}", "non-existent-id"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Should search users")
    void searchUsers_returnsResults() throws Exception {
        mockMvc.perform(get("/api/v1/users/search").param("q", "test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].email").value("test@example.com"));
    }

    @Test
    @DisplayName("Should toggle admin role")
    void toggleAdmin_togglesRole() throws Exception {
        // First toggle on
        mockMvc.perform(patch("/api/v1/users/{userId}/admin", testUser.getUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(true));

        // Toggle off (now we have 2 admins so it's allowed)
        mockMvc.perform(patch("/api/v1/users/{userId}/admin", testUser.getUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(false));
    }

    @Test
    @DisplayName("Should toggle env admin role")
    void toggleEnvAdmin_togglesRole() throws Exception {
        mockMvc.perform(patch("/api/v1/users/{userId}/env-admin", testUser.getUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.envAdmin").value(true));
    }

    @Test
    @DisplayName("Should update user roles via PATCH")
    void updateUserRoles_updatesRoles() throws Exception {
        UpdateUserRoleDTO dto = new UpdateUserRoleDTO(false, true);

        mockMvc.perform(patch("/api/v1/users/{userId}/roles", testUser.getUserId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admin").value(false))
                .andExpect(jsonPath("$.envAdmin").value(true));
    }

    @Test
    @DisplayName("Should deactivate user")
    void deactivateUser_deactivates() throws Exception {
        mockMvc.perform(delete("/api/v1/users/{userId}", testUser.getUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    @DisplayName("Should reactivate user")
    void reactivateUser_reactivates() throws Exception {
        // First deactivate
        testUser.setIsActive(false);
        userRepository.save(testUser);

        // Then reactivate
        mockMvc.perform(post("/api/v1/users/{userId}/reactivate", testUser.getUserId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("Should list admin users")
    void listAdminUsers_returnsAdmins() throws Exception {
        mockMvc.perform(get("/api/v1/users/admins"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].email").value("admin@example.com"));
    }

    @Test
    @DisplayName("Should filter inactive users when requested")
    void listUsers_excludeInactive_filtersCorrectly() throws Exception {
        // Deactivate test user
        testUser.setIsActive(false);
        userRepository.save(testUser);

        // Default should exclude inactive
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));

        // Include inactive
        mockMvc.perform(get("/api/v1/users").param("includeInactive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    // ---- U13-T01: department and project code

    @Test
    @DisplayName("An onboarded person keeps their department and project code")
    void onboardWithDepartmentAndProjectCode() throws Exception {
        mockMvc.perform(post("/api/v1/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"neha@example.com\",\"displayName\":\"Neha Gupta\","
                                + "\"department\":\"Engineering\",\"projectCode\":\"DLK-204\"}"))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.email == 'neha@example.com')].department").value("Engineering"))
                .andExpect(jsonPath("$[?(@.email == 'neha@example.com')].projectCode").value("DLK-204"));
    }

    @Test
    @DisplayName("PATCH /profile sets, validates and clears department and project code")
    void profilePatch() throws Exception {
        mockMvc.perform(patch("/api/v1/users/{userId}/profile", testUser.getUserId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"department\":\" Finance \",\"projectCode\":\"FIN_7.2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.department").value("Finance"))
                .andExpect(jsonPath("$.projectCode").value("FIN_7.2"));

        mockMvc.perform(patch("/api/v1/users/{userId}/profile", testUser.getUserId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"department\":\"Finance\",\"projectCode\":\"DLK 204\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Project code can only use letters, digits, dot, dash and underscore."));

        mockMvc.perform(patch("/api/v1/users/{userId}/profile", testUser.getUserId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"department\":\"\",\"projectCode\":\"  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.department").doesNotExist())
                .andExpect(jsonPath("$.projectCode").doesNotExist());
    }
}
