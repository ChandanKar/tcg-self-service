package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.SpotPolicy;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spot opt-in API (E31-T03): the switch, who may change it, the acknowledgement, production and exclusions. */
class SpotControllerTest extends SecuredWebTestBase {

    @Autowired private SettingsService settings;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment env;
    private Environment other;

    @BeforeEach
    void seed() {
        env = newEnvironment("SpotEnv");
        other = newEnvironment("OtherEnv");
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);
        settings.set("spot.enabled", "true", admin.getUserId());
    }

    @AfterEach
    void switchOff() {
        settings.reset("spot.enabled", admin.getUserId());
    }

    private String policyUrl(Environment e) {
        return "/api/v1/environments/" + e.getEnvironmentId() + "/spot-policy";
    }

    @Test
    void anEnvAdminAllowsSpotWithTheAcknowledgementAndItIsAudited() throws Exception {
        mockMvc.perform(put(policyUrl(env)).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policy\":\"ALLOWED\",\"acknowledgeInterruptible\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spotPolicy").value("ALLOWED"));

        assertThat(environmentRepository.findById(env.getEnvironmentId()).orElseThrow().getSpotPolicy()).isEqualTo(SpotPolicy.ALLOWED);
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action_type = 'SPOT_POLICY_CHANGED' AND environment_id = ?",
                Integer.class, env.getEnvironmentId())).isEqualTo(1));
    }

    @Test
    void withoutTheAcknowledgementItIsRefused() throws Exception {
        expectError(mockMvc.perform(put(policyUrl(env)).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":\"ALLOWED\"}")), 400);
        expectError(mockMvc.perform(put(policyUrl(env)).with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":\"SOMETIMES\"}")), 400);
    }

    @Test
    void aProductionEnvironmentCannotUseSpot() throws Exception {
        Environment prod = environmentRepository.findById(env.getEnvironmentId()).orElseThrow();
        prod.setIsProduction(true);
        environmentRepository.save(prod);

        expectError(mockMvc.perform(put(policyUrl(env)).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":\"ALLOWED\",\"acknowledgeInterruptible\":true}")), 400);
    }

    @Test
    void usersAndViewersCannotChangeIt() throws Exception {
        String body = "{\"policy\":\"ALLOWED\",\"acknowledgeInterruptible\":true}";
        mockMvc.perform(put(policyUrl(env)).with(asUser()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(policyUrl(env)).with(asViewer()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void turnedOffNobodyCanChangeIt() throws Exception {
        settings.reset("spot.enabled", admin.getUserId());

        expectError(mockMvc.perform(put(policyUrl(env)).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":\"ALLOWED\",\"acknowledgeInterruptible\":true}")), 400);
    }

    @Test
    void aVmIsExcludedOnlyThroughItsOwnEnvironment() throws Exception {
        Vm vm = newVm(newGroup(env, "workers"), "worker-1", VmStatus.RUNNING);

        mockMvc.perform(put("/api/v1/environments/" + env.getEnvironmentId() + "/vms/" + vm.getVmId() + "/spot-excluded")
                        .with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"excluded\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.spotExcluded").value(true));
        assertThat(vmRepository.findById(vm.getVmId()).orElseThrow().isSpotExcluded()).isTrue();

        expectError(mockMvc.perform(put("/api/v1/environments/" + other.getEnvironmentId() + "/vms/" + vm.getVmId() + "/spot-excluded")
                .with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"excluded\":false}")), 404);
    }

    // ---- Spot summary (E31-T08) ----

    @Test
    void aViewerSeesTheSummaryWithoutActionsAndCannotChangeAnything() throws Exception {
        newVm(newGroup(env, "web"), "web-1", VmStatus.RUNNING);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/environments/" + env.getEnvironmentId() + "/spot-summary").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy").value("NONE"))
                .andExpect(jsonPath("$.canAdminister").value(false))
                .andExpect(jsonPath("$.interruptions30d").value(0));
        mockMvc.perform(put(policyUrl(env)).with(asViewer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policy\":\"ALLOWED\",\"acknowledgeInterruptible\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anAdministratorSeesThatTheyCanAct() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/environments/" + env.getEnvironmentId() + "/spot-summary").with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canAdminister").value(true));
    }

    @Test
    void theSummaryIsNotFoundWhileSpotIsOffOrWithoutAccess() throws Exception {
        expectError(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/environments/" + other.getEnvironmentId() + "/spot-summary").with(asUser())), 403);

        settings.reset("spot.enabled", admin.getUserId());
        expectError(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/environments/" + env.getEnvironmentId() + "/spot-summary").with(asViewer())), 404);
    }

    // ---- U11-T08: sizes, savings, recent interruptions and how long the standby ran

    @Autowired private com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository stateHistory;
    @Autowired private com.tcgdigital.vmcontrol.repository.AuditLogRepository auditLogs;

    @Test
    void theSummaryListsSizesAndTheInterruptionsWithHowLongTheStandbyRan() throws Exception {
        com.tcgdigital.vmcontrol.model.VmGroup nodes = newGroup(env, "nodes");
        Vm spot = newVm(nodes, "app-nodes-spot", VmStatus.RUNNING);
        spot.setProvider(com.tcgdigital.vmcontrol.model.CloudProvider.AWS_EKS);
        spot.setPurchaseOption(com.tcgdigital.vmcontrol.model.PurchaseOption.SPOT);
        spot.setMetadata("{\"instanceType\":\"c6i.xlarge\",\"desiredSize\":2}");
        spot = vmRepository.saveAndFlush(spot);
        Vm onDemand = newVm(nodes, "batch-nodes", VmStatus.RUNNING);
        onDemand.setProvider(com.tcgdigital.vmcontrol.model.CloudProvider.AWS_EKS);
        onDemand.setMetadata("{\"instanceType\":\"c6i.2xlarge\",\"desiredSize\":3}");
        vmRepository.saveAndFlush(onDemand);

        // Two interruptions of app-nodes-spot: the older one's standby ran 22 minutes, the newer still runs.
        String older = interruption(spot, 3 * 24 * 60);
        String newer = interruption(spot, 30);
        com.tcgdigital.vmcontrol.model.AuditLog cleared = auditLogs.saveAndFlush(com.tcgdigital.vmcontrol.model.AuditLog.builder()
                .logId(java.util.UUID.randomUUID().toString())
                .action(com.tcgdigital.vmcontrol.model.AuditAction.SPOT_FALLBACK_CLEARED)
                .targetType("vm").targetId(spot.getVmId()).targetName("app-nodes-spot")
                .details("Spot capacity is back").build());
        jdbcTemplate.update("UPDATE audit_log SET created_at = DATE_SUB(UTC_TIMESTAMP(), INTERVAL ? MINUTE) WHERE audit_id = ?",
                3 * 24 * 60 - 22, cleared.getLogId());
        // An older one, outside the 30 days, is not listed.
        interruption(spot, 40 * 24 * 60);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/environments/" + env.getEnvironmentId() + "/spot-summary").with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallbackGraceMinutes").value(10))
                .andExpect(jsonPath("$.interruptions30d").value(2))
                .andExpect(jsonPath("$.vms[?(@.name == 'app-nodes-spot')].instanceType").value("c6i.xlarge"))
                .andExpect(jsonPath("$.vms[?(@.name == 'app-nodes-spot')].nodeCount").value(2))
                .andExpect(jsonPath("$.vms[?(@.name == 'batch-nodes')].nodeCount").value(3))
                .andExpect(jsonPath("$.vms[?(@.name == 'batch-nodes')].purchaseOption").value("ON_DEMAND"))
                .andExpect(jsonPath("$.recentInterruptions.length()").value(2))
                .andExpect(jsonPath("$.recentInterruptions[0].vmName").value("app-nodes-spot"))
                .andExpect(jsonPath("$.recentInterruptions[0].standbyMinutes").doesNotExist())
                .andExpect(jsonPath("$.recentInterruptions[1].standbyMinutes").value(22));
        assertThat(older).isNotEqualTo(newer);
    }

    /** A Spot interruption of the VM, so many minutes ago. */
    private String interruption(Vm vm, int minutesAgo) {
        com.tcgdigital.vmcontrol.model.VmStateHistory h = stateHistory.saveAndFlush(com.tcgdigital.vmcontrol.model.VmStateHistory.builder()
                .historyId(java.util.UUID.randomUUID().toString())
                .vm(vm).previousStatus(VmStatus.RUNNING).newStatus(VmStatus.RUNNING)
                .changeSource(com.tcgdigital.vmcontrol.service.SpotInterruptionRecorder.CHANGE_SOURCE)
                .details("Spot capacity lost").build());
        jdbcTemplate.update("UPDATE vm_state_history SET created_at = DATE_SUB(UTC_TIMESTAMP(), INTERVAL ? MINUTE) WHERE history_id = ?",
                minutesAgo, h.getHistoryId());
        return h.getHistoryId();
    }
}
