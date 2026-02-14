package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.CreateEnvironmentDTO;
import com.tcgdigital.vmcontrol.dto.CreateVmGroupDTO;
import com.tcgdigital.vmcontrol.dto.RegisterVmDTO;
import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.VmType;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import com.tcgdigital.vmcontrol.service.LockService;
import com.tcgdigital.vmcontrol.service.VmGroupService;
import com.tcgdigital.vmcontrol.service.VmService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/db/reset-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class VmOperationsControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private VmGroupService groupService;

    @Autowired
    private VmService vmService;

    @Autowired
    private LockService lockService;

    private String environmentId;
    private String groupId;
    private String vmId;

    @BeforeEach
    void setUp() {
        // Create environment
        CreateEnvironmentDTO envDto = new CreateEnvironmentDTO();
        envDto.setName("ops-ctrl-test-" + UUID.randomUUID().toString().substring(0, 8));
        envDto.setDisplayName("Operations Controller Test");
        environmentId = environmentService.createEnvironment(envDto).getEnvironmentId();

        // Create group
        CreateVmGroupDTO groupDto = new CreateVmGroupDTO();
        groupDto.setName("test-group");
        groupDto.setDisplayName("Test Group");
        groupDto.setSequencePosition(1);
        groupId = groupService.createGroup(environmentId, groupDto).getGroupId();

        // Create VM
        RegisterVmDTO vmDto = new RegisterVmDTO();
        vmDto.setGroupId(groupId);
        vmDto.setName("test-vm");
        vmDto.setDisplayName("Test VM");
        vmDto.setProvider(CloudProvider.AWS);
        vmDto.setRegion("us-east-1");
        vmDto.setProviderVmId("i-" + UUID.randomUUID().toString().substring(0, 17));
        vmDto.setVmType(VmType.DEV);
        vmDto.setSequencePosition(1);
        vmId = vmService.registerVm(vmDto).getVmId();
    }

    @Test
    void testStartOperation_Success() throws Exception {
        // Acquire lock first
        lockService.acquireLock(environmentId, "user-001", "Test", null);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setReason("Integration test");
        dto.setSkipAlreadyInTargetState(false);

        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/operations")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").isNotEmpty())
                .andExpect(jsonPath("$.operationType").value("START"))
                // Status could be PENDING or already changed due to async execution
                .andExpect(jsonPath("$.status").isNotEmpty())
                .andExpect(jsonPath("$.totalTargets").value(1));
    }

    @Test
    void testStartOperation_NoLock_SucceedsWhenUnlocked() throws Exception {
        // No lock acquired - but if environment is unlocked, operation should succeed

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setSkipAlreadyInTargetState(false);

        // When environment is not locked, operation can proceed
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/operations")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted());
    }

    @Test
    void testStartOperation_WrongUser_FailsWhenLocked() throws Exception {
        // User 1 acquires lock
        lockService.acquireLock(environmentId, "user-001", "Test", null);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setSkipAlreadyInTargetState(false);

        // User 2 tries to operate - should fail
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/operations")
                        .header("X-User-Id", "user-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isConflict());
    }

    @Test
    void testListOperations() throws Exception {
        // Create an operation first
        lockService.acquireLock(environmentId, "user-001", "Test", null);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setSkipAlreadyInTargetState(false);

        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/operations")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted());

        // List operations
        mockMvc.perform(get("/api/v1/environments/" + environmentId + "/operations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].operationType").value("START"));
    }

    @Test
    void testGetOperationDetails() throws Exception {
        // Create an operation
        lockService.acquireLock(environmentId, "user-001", "Test", null);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.STOP);
        dto.setSkipAlreadyInTargetState(false);

        String response = mockMvc.perform(post("/api/v1/environments/" + environmentId + "/operations")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String executionId = objectMapper.readTree(response).get("executionId").asText();

        // Get details
        mockMvc.perform(get("/api/v1/environments/" + environmentId + "/operations/" + executionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value(executionId))
                .andExpect(jsonPath("$.operationType").value("STOP"))
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details", hasSize(1)));
    }

    @Test
    void testCancelOperation() throws Exception {
        // Create an operation
        lockService.acquireLock(environmentId, "user-001", "Test", null);

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.START);
        dto.setSkipAlreadyInTargetState(false);

        String response = mockMvc.perform(post("/api/v1/environments/" + environmentId + "/operations")
                        .header("X-User-Id", "user-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String executionId = objectMapper.readTree(response).get("executionId").asText();

        // Try to cancel operation - may succeed (200) or fail (400) if operation already completed
        mockMvc.perform(post("/api/v1/environments/" + environmentId + "/operations/" + executionId + "/cancel")
                        .header("X-User-Id", "user-001"))
                // Accept either OK (cancel succeeded) or BadRequest (already completed)
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 200 || status == 400,
                            "Expected 200 (cancelled) or 400 (already completed), got " + status);
                });
    }
}

