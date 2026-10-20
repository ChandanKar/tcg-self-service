package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.CreateEnvironmentDTO;
import com.tcgdigital.vmcontrol.dto.CreateVmGroupDTO;
import com.tcgdigital.vmcontrol.dto.GrantAccessDTO;
import com.tcgdigital.vmcontrol.dto.RegisterVmDTO;
import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.VmType;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import com.tcgdigital.vmcontrol.service.LockService;
import com.tcgdigital.vmcontrol.service.VmGroupService;
import com.tcgdigital.vmcontrol.service.VmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class VmOperationsControllerIntegrationTest extends AbstractIntegrationTest {

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

    @Autowired
    private EnvironmentAccessService accessService;

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

        // Grant user-001 and user-002 access so security checks pass in tests
        accessService.grantAccess(environmentId, "admin-001", new GrantAccessDTO("user001@test.com", AccessLevel.USER, null, null));
        accessService.grantAccess(environmentId, "admin-001", new GrantAccessDTO("user002@test.com", AccessLevel.USER, null, null));

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
        mockMvc.perform(get("/api/v1/environments/" + environmentId + "/operations")
                        .header("X-User-Id", "user-001"))
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
        mockMvc.perform(get("/api/v1/environments/" + environmentId + "/operations/" + executionId)
                        .header("X-User-Id", "user-001"))
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

    // ---------------------------------------------------------------- U08-T01: History page, group names

    @Autowired
    private com.tcgdigital.vmcontrol.repository.OperationExecutionRepository executionRepository;

    @Autowired
    private com.tcgdigital.vmcontrol.repository.OperationDetailRepository detailRepository;

    private com.tcgdigital.vmcontrol.model.OperationExecution run(int minutesAgo) {
        com.tcgdigital.vmcontrol.model.OperationExecution e = new com.tcgdigital.vmcontrol.model.OperationExecution();
        e.setExecutionId(UUID.randomUUID().toString());
        e.setEnvironment(environmentRepository.findById(environmentId).orElseThrow());
        e.setOperationType(OperationType.START);
        e.setStatus(com.tcgdigital.vmcontrol.model.ExecutionStatus.COMPLETED);
        e.setInitiatedByUserId("user-001");
        e.setStartedAt(java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60L * minutesAgo)));
        e.setTotalTargets(1);
        return executionRepository.saveAndFlush(e);
    }

    @Test
    void theHistoryPagesRunsNewestFirstWithWhatTheyCovered() throws Exception {
        for (int i = 0; i < 23; i++) run(100 - i);
        com.tcgdigital.vmcontrol.model.OperationExecution newest = run(1);
        com.tcgdigital.vmcontrol.model.OperationDetail step = new com.tcgdigital.vmcontrol.model.OperationDetail();
        step.setDetailId(UUID.randomUUID().toString());
        step.setExecution(newest);
        // Only the target id is stored (OperationDetail.vm is transient); the VM is looked up from it.
        step.setTargetType("vm");
        step.setTargetId(vmId);
        step.setTargetName("Test VM");
        step.setAction("start");
        step.setSequencePosition(1);
        step.setStatus("completed");
        detailRepository.saveAndFlush(step);

        mockMvc.perform(get("/api/v1/environments/" + environmentId + "/operations/page?page=0&size=7")
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(7)))
                .andExpect(jsonPath("$.page.totalElements").value(24))
                .andExpect(jsonPath("$.content[0].executionId").value(newest.getExecutionId()))
                .andExpect(jsonPath("$.content[0].scopeLabel").value("Test VM"))
                .andExpect(jsonPath("$.content[0].triggerName").value(nullValue()))
                .andExpect(jsonPath("$.content[0].details").value(nullValue()));
        mockMvc.perform(get("/api/v1/environments/" + environmentId + "/operations/page?size=500")
                        .header("X-User-Id", "user-001"))
                .andExpect(jsonPath("$.content", hasSize(24)))
                .andExpect(jsonPath("$.page.size").value(50));

        mockMvc.perform(get("/api/v1/environments/" + environmentId + "/operations/" + newest.getExecutionId())
                        .header("X-User-Id", "user-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.details[0].groupId").value(groupId))
                .andExpect(jsonPath("$.details[0].groupName").value("Test Group"));
    }

    @Test
    void theHistoryPageNeedsAccessToTheEnvironment() throws Exception {
        mockMvc.perform(get("/api/v1/environments/" + environmentId + "/operations/page").header("X-User-Id", "dev-user-001"))
                .andExpect(status().isForbidden());
    }
}
