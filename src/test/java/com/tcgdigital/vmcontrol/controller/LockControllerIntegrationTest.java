package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.AcquireLockDTO;
import com.tcgdigital.vmcontrol.dto.BreakLockDTO;
import com.tcgdigital.vmcontrol.dto.CreateEnvironmentDTO;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/db/reset-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class LockControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EnvironmentService environmentService;

    private String environmentId;

    @BeforeEach
    void setUp() throws Exception {
        // Create a test environment with unique name
        CreateEnvironmentDTO dto = new CreateEnvironmentDTO();
        dto.setName("lock-test-env-" + UUID.randomUUID().toString().substring(0, 8));
        dto.setDisplayName("Lock Test Environment");
        environmentId = environmentService.createEnvironment(dto).getEnvironmentId();
    }

    @Test
    void testGetLockStatus_NoLock() throws Exception {
        mockMvc.perform(get("/api/v1/environments/" + environmentId + "/lock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.lockId").doesNotExist());
    }

    @Test
    void testAcquireLock_Success() throws Exception {
        AcquireLockDTO dto = new AcquireLockDTO();
        dto.setReason("Testing lock acquisition");
        dto.setExpectedDurationMinutes(30);

        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/acquire")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.lockId").isNotEmpty())
                .andExpect(jsonPath("$.lockedByUserId").value("user-001"))
                .andExpect(jsonPath("$.lockReason").value("Testing lock acquisition"));
    }

    @Test
    void testAcquireLock_Conflict() throws Exception {
        // First user acquires lock
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/acquire")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // Second user tries to acquire
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/acquire")
                        .header("X-User-Id", "user-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Lock Conflict"))
                .andExpect(jsonPath("$.lockedByUserId").value("user-001"));
    }

    @Test
    void testReleaseLock_Success() throws Exception {
        // Acquire lock
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/acquire")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // Release lock
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/release")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk());

        // Verify lock is released
        mockMvc.perform(get("/api/v1/environments/" + environmentId + "/lock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));
    }

    @Test
    void testReleaseLock_WrongUser() throws Exception {
        // User 1 acquires lock
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/acquire")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // User 2 tries to release
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/release")
                        .header("X-User-Id", "user-002"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    void testBreakLock_Success() throws Exception {
        // User acquires lock
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/acquire")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // Admin breaks lock
        BreakLockDTO dto = new BreakLockDTO();
        dto.setReason("Emergency maintenance required");

        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/break")
                        .header("X-User-Id", "admin-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        // Verify lock is broken
        mockMvc.perform(get("/api/v1/environments/" + environmentId + "/lock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));
    }

    @Test
    void testBreakLock_RequiresReason() throws Exception {
        // User acquires lock
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/acquire")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // Try to break without reason
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/break")
                        .header("X-User-Id", "admin-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void testGetLockHistory() throws Exception {
        // Acquire and release lock
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/acquire")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/release")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk());

        // Get history
        mockMvc.perform(get("/api/v1/environments/" + environmentId + "/lock/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].action").value("RELEASED"))
                .andExpect(jsonPath("$[1].action").value("ACQUIRED"));
    }

    @Test
    void testLockWorkflow_FullCycle() throws Exception {
        // 1. Check no lock exists
        mockMvc.perform(get("/api/v1/environments/" + environmentId + "/lock"))
                .andExpect(jsonPath("$.locked").value(false));

        // 2. User 1 acquires lock
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/acquire")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Deployment\", \"expectedDurationMinutes\": 15}"))
                .andExpect(status().isOk());

        // 3. User 2 cannot acquire
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/acquire")
                        .header("X-User-Id", "user-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());

        // 4. User 1 releases lock
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/release")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk());

        // 5. User 2 can now acquire
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/lock/acquire")
                        .header("X-User-Id", "user-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lockedByUserId").value("user-002"));
    }
}
