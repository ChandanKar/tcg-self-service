package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.CreateEnvironmentDTO;
import com.tcgdigital.vmcontrol.dto.CreateVmGroupDTO;
import com.tcgdigital.vmcontrol.dto.RegisterVmDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.VmType;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username = "admin001@test.com")
@Sql(scripts = "/db/reset-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class EnvironmentHierarchyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private VmGroupRepository groupRepository;

    @Autowired
    private VmRepository vmRepository;

    @BeforeEach
    void setUp() {
        vmRepository.deleteAll();
        groupRepository.deleteAll();
        environmentRepository.deleteAll();
    }

    // ==================== Environment Tests ====================

    @Test
    void testCreateEnvironment_Success() throws Exception {
        CreateEnvironmentDTO dto = new CreateEnvironmentDTO();
        dto.setName("test-environment");
        dto.setDisplayName("Test Environment");
        dto.setDescription("A test environment for integration testing");

        mockMvc.perform(post("/api/v1/environments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.environmentId").isNotEmpty())
                .andExpect(jsonPath("$.name").value("test-environment"))
                .andExpect(jsonPath("$.displayName").value("Test Environment"))
                .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    void testCreateEnvironment_DuplicateName_BadRequest() throws Exception {
        CreateEnvironmentDTO dto = new CreateEnvironmentDTO();
        dto.setName("duplicate-env");
        dto.setDisplayName("Duplicate Environment");

        // Create first
        mockMvc.perform(post("/api/v1/environments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated());

        // Try to create duplicate
        mockMvc.perform(post("/api/v1/environments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation Error"));
    }

    @Test
    void testListEnvironments_ReturnsActiveOnly() throws Exception {
        // Create active environment
        CreateEnvironmentDTO activeDto = new CreateEnvironmentDTO();
        activeDto.setName("active-env");
        activeDto.setDisplayName("Active Environment");

        MvcResult result = mockMvc.perform(post("/api/v1/environments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(activeDto)))
                .andExpect(status().isCreated())
                .andReturn();

        String envId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("environmentId").asText();

        // Create and deactivate another
        CreateEnvironmentDTO inactiveDto = new CreateEnvironmentDTO();
        inactiveDto.setName("inactive-env");
        inactiveDto.setDisplayName("Inactive Environment");

        MvcResult inactiveResult = mockMvc.perform(post("/api/v1/environments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(inactiveDto)))
                .andExpect(status().isCreated())
                .andReturn();

        String inactiveEnvId = objectMapper.readTree(inactiveResult.getResponse().getContentAsString())
                .get("environmentId").asText();

        mockMvc.perform(delete("/api/v1/environments/" + inactiveEnvId))
                .andExpect(status().isNoContent());

        // List active only (default)
        mockMvc.perform(get("/api/v1/environments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("active-env"));

        // List including inactive
        mockMvc.perform(get("/api/v1/environments?includeInactive=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
    }

    // ==================== Group Tests ====================

    @Test
    void testCreateGroup_Success() throws Exception {
        String envId = createTestEnvironment("group-test-env");

        CreateVmGroupDTO dto = new CreateVmGroupDTO();
        dto.setName("data-tier");
        dto.setDisplayName("Data Tier");
        dto.setDescription("Database servers");
        dto.setSequencePosition(1);

        mockMvc.perform(post("/api/v1/environments/" + envId + "/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").isNotEmpty())
                .andExpect(jsonPath("$.name").value("data-tier"))
                .andExpect(jsonPath("$.sequencePosition").value(1));
    }

    @Test
    void testCreateGroup_WithDependencies() throws Exception {
        String envId = createTestEnvironment("dep-test-env");

        // Create first group
        CreateVmGroupDTO group1Dto = new CreateVmGroupDTO();
        group1Dto.setName("data-tier");
        group1Dto.setDisplayName("Data Tier");
        group1Dto.setSequencePosition(1);

        MvcResult result = mockMvc.perform(post("/api/v1/environments/" + envId + "/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(group1Dto)))
                .andExpect(status().isCreated())
                .andReturn();

        String group1Id = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("groupId").asText();

        // Create second group depending on first
        CreateVmGroupDTO group2Dto = new CreateVmGroupDTO();
        group2Dto.setName("backend-tier");
        group2Dto.setDisplayName("Backend Tier");
        group2Dto.setSequencePosition(2);
        group2Dto.setDependsOnGroupIds(java.util.List.of(group1Id));

        mockMvc.perform(post("/api/v1/environments/" + envId + "/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(group2Dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dependsOnGroupIds", hasSize(1)))
                .andExpect(jsonPath("$.dependsOnGroupIds[0]").value(group1Id));
    }

    @Test
    void testGetGroupsInStartOrder() throws Exception {
        String envId = createTestEnvironment("start-order-env");

        // Create groups with dependencies
        String group1Id = createTestGroup(envId, "data-tier", 1, null);
        String group2Id = createTestGroup(envId, "backend-tier", 2, java.util.List.of(group1Id));
        String group3Id = createTestGroup(envId, "frontend-tier", 3, java.util.List.of(group2Id));

        mockMvc.perform(get("/api/v1/environments/" + envId + "/groups/start-order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].groupId").value(group1Id))
                .andExpect(jsonPath("$[1].groupId").value(group2Id))
                .andExpect(jsonPath("$[2].groupId").value(group3Id));
    }

    // ==================== VM Tests ====================

    @Test
    void testRegisterVm_Success() throws Exception {
        String envId = createTestEnvironment("vm-test-env");
        String groupId = createTestGroup(envId, "test-group", 1, null);

        RegisterVmDTO dto = new RegisterVmDTO();
        dto.setGroupId(groupId);
        dto.setName("db-primary");
        dto.setDisplayName("Primary Database");
        dto.setProvider(CloudProvider.AWS);
        dto.setRegion("us-east-1");
        dto.setProviderVmId("i-1234567890abcdef0");
        dto.setVmType(VmType.DEV);
        dto.setSequencePosition(1);

        mockMvc.perform(post("/api/v1/environments/" + envId + "/vms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.vmId").isNotEmpty())
                .andExpect(jsonPath("$.name").value("db-primary"))
                .andExpect(jsonPath("$.provider").value("AWS"))
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    @Test
    void testListVms_GroupedByGroup() throws Exception {
        String envId = createTestEnvironment("list-vm-env");
        String group1Id = createTestGroup(envId, "group1", 1, null);
        String group2Id = createTestGroup(envId, "group2", 2, null);

        // Create VMs in different groups
        createTestVm(envId, group1Id, "vm1", 1);
        createTestVm(envId, group1Id, "vm2", 2);
        createTestVm(envId, group2Id, "vm3", 1);

        mockMvc.perform(get("/api/v1/environments/" + envId + "/vms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].group.name").value("group1"))
                .andExpect(jsonPath("$[0].vms", hasSize(2)))
                .andExpect(jsonPath("$[1].group.name").value("group2"))
                .andExpect(jsonPath("$[1].vms", hasSize(1)));
    }

    // ==================== Helper Methods ====================

    private String createTestEnvironment(String name) throws Exception {
        CreateEnvironmentDTO dto = new CreateEnvironmentDTO();
        dto.setName(name);
        dto.setDisplayName(name);

        MvcResult result = mockMvc.perform(post("/api/v1/environments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("environmentId").asText();
    }

    private String createTestGroup(String envId, String name, int sequence, java.util.List<String> dependsOn) throws Exception {
        CreateVmGroupDTO dto = new CreateVmGroupDTO();
        dto.setName(name);
        dto.setDisplayName(name);
        dto.setSequencePosition(sequence);
        dto.setDependsOnGroupIds(dependsOn);

        MvcResult result = mockMvc.perform(post("/api/v1/environments/" + envId + "/groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("groupId").asText();
    }

    private String createTestVm(String envId, String groupId, String name, int sequence) throws Exception {
        RegisterVmDTO dto = new RegisterVmDTO();
        dto.setGroupId(groupId);
        dto.setName(name);
        dto.setDisplayName(name);
        dto.setProvider(CloudProvider.AWS);
        dto.setRegion("us-east-1");
        dto.setProviderVmId("i-" + java.util.UUID.randomUUID().toString().substring(0, 17));
        dto.setVmType(VmType.DEV);
        dto.setSequencePosition(sequence);

        MvcResult result = mockMvc.perform(post("/api/v1/environments/" + envId + "/vms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("vmId").asText();
    }
}

