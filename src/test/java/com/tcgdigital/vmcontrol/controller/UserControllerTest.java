package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.UpdateUserRoleDTO;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for UserController.
 * Note: These tests run with security disabled (entraid.enabled=false in test profile),
 * so authorization checks are bypassed. Authorization is tested via the @PreAuthorize
 * annotations which are enforced when security is enabled.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class UserControllerTest {

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
    }

    @Test
    @DisplayName("Should list all users")
    void listUsers_returnsUsers() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].email", containsInAnyOrder("test@example.com", "admin@example.com")));
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
}




