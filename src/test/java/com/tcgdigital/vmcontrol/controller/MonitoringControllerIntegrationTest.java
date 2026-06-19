package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.*;
import com.tcgdigital.vmcontrol.service.AwsCloudProviderService;
import com.tcgdigital.vmcontrol.service.CloudProviderFactory;
import com.tcgdigital.vmcontrol.service.StateSyncService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = "/db/reset-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class MonitoringControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private VmStateHistoryRepository stateHistoryRepository;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private VmGroupRepository groupRepository;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private StateSyncService stateSyncService;

    @MockBean
    private CloudProviderFactory cloudProviderFactory;

    @MockBean
    private AwsCloudProviderService awsCloudProviderService;

    private Environment testEnvironment;
    private VmGroup testGroup;
    private Vm testVm;

    @BeforeEach
    void setUp() {
        stateHistoryRepository.deleteAll();
        vmRepository.deleteAll();
        groupRepository.deleteAll();
        environmentRepository.deleteAll();

        // Create test data
        testEnvironment = new Environment();
        testEnvironment.setEnvironmentId(UUID.randomUUID().toString());
        testEnvironment.setName("monitor-test-env");
        testEnvironment.setDisplayName("Monitoring Test Environment");
        testEnvironment.setIsActive(true);
        testEnvironment = environmentRepository.save(testEnvironment);

        testGroup = new VmGroup();
        testGroup.setGroupId(UUID.randomUUID().toString());
        testGroup.setEnvironment(testEnvironment);
        testGroup.setName("monitor-test-group");
        testGroup.setDisplayName("Monitoring Test Group");
        testGroup.setSequencePosition(1);
        testGroup = groupRepository.save(testGroup);

        testVm = new Vm();
        testVm.setVmId(UUID.randomUUID().toString());
        testVm.setGroup(testGroup);
        testVm.setName("monitor-test-vm");
        testVm.setDisplayName("Monitoring Test VM");
        testVm.setProvider(CloudProvider.AWS);
        testVm.setRegion("ap-south-1");
        testVm.setProviderVmId("i-monitor123");
        testVm.setSequencePosition(1);
        testVm.setStatus(VmStatus.RUNNING);
        testVm = vmRepository.save(testVm);

        // Setup mock cloud provider
        when(cloudProviderFactory.getService(CloudProvider.AWS)).thenReturn(awsCloudProviderService);
        when(awsCloudProviderService.isAvailable()).thenReturn(true);
        when(awsCloudProviderService.getVmStatus("i-monitor123", "ap-south-1")).thenReturn(VmStatus.RUNNING);

        // Create some state history - use null for changedByUserId to avoid FK constraint
        stateSyncService.recordStateChange(testVm, VmStatus.STOPPED, VmStatus.RUNNING, "user_action", null, null, "VM started");
    }

    @Test
    void testGetSyncStatus() throws Exception {
        mockMvc.perform(get("/api/v1/monitoring/sync-status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSyncStatus").isNotEmpty())
                .andExpect(jsonPath("$.syncInProgress").value(false));
    }

    @Test
    void testTriggerSync() throws Exception {
        mockMvc.perform(post("/api/v1/monitoring/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastSyncStatus").value("success"))
                .andExpect(jsonPath("$.totalVmsSynced").value(greaterThanOrEqualTo(0)));
    }

    @Test
    void testSyncEnvironment() throws Exception {
        mockMvc.perform(post("/api/v1/monitoring/sync/environment/" + testEnvironment.getEnvironmentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environmentId").value(testEnvironment.getEnvironmentId()))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.driftDetected").isNumber());
    }

    @Test
    void testGetVmStateHistory() throws Exception {
        mockMvc.perform(get("/api/v1/monitoring/vms/" + testVm.getVmId() + "/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].vmId").value(testVm.getVmId()))
                .andExpect(jsonPath("$.content[0].newStatus").value("RUNNING"));
    }

    @Test
    void testGetRecentStateChanges() throws Exception {
        mockMvc.perform(get("/api/v1/monitoring/state-changes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    void testGetDriftEvents() throws Exception {
        // Create a drift event
        stateSyncService.recordStateChange(testVm, VmStatus.RUNNING, VmStatus.STOPPED, "state_sync", null, null, "Drift detected");

        mockMvc.perform(get("/api/v1/monitoring/drift-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].drift").value(true));
    }

    @Test
    void testCountDriftEvents() throws Exception {
        // Create drift events
        stateSyncService.recordStateChange(testVm, VmStatus.RUNNING, VmStatus.STOPPED, "state_sync", null, null, "Drift 1");
        stateSyncService.recordStateChange(testVm, VmStatus.STOPPED, VmStatus.RUNNING, "state_sync", null, null, "Drift 2");

        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_DATE);

        mockMvc.perform(get("/api/v1/monitoring/drift-events/count")
                        .param("startDate", yesterday)
                        .param("endDate", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.driftEventCount").value(greaterThanOrEqualTo(2)));
    }

    @Test
    void testGetDriftEventsReport() throws Exception {
        // Create drift event
        stateSyncService.recordStateChange(testVm, VmStatus.RUNNING, VmStatus.STOPPED, "state_sync", null, null, "Drift for report");

        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        String yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_DATE);

        mockMvc.perform(get("/api/v1/monitoring/drift-events/report")
                        .param("startDate", yesterday)
                        .param("endDate", today))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$[0].changeSource").value("state_sync"));
    }

    @Test
    void testVmStateHistory_Pagination() throws Exception {
        // Create multiple history entries
        for (int i = 0; i < 5; i++) {
            stateSyncService.recordStateChange(testVm, VmStatus.STOPPED, VmStatus.RUNNING, "user_action", null, null, "Change " + i);
        }

        mockMvc.perform(get("/api/v1/monitoring/vms/" + testVm.getVmId() + "/history")
                        .param("page", "0")
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)))
                .andExpect(jsonPath("$.page.totalElements").value(greaterThanOrEqualTo(5)));
    }
}

