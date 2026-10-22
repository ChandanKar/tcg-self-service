package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.Ec2InstanceInfo;
import com.tcgdigital.vmcontrol.dto.RdsDatabaseInfoDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U11-T01: "Add VMs to <environment>" lists the instances of a region with what each is to the
 * environment, by name and reference; the cloud ids reach administrators only.
 */
class VmCandidatesControllerTest extends SecuredWebTestBase {

    static final String REGION = "ap-south-1";
    static final String FREE_ID = "i-0aaaaaaaaaaaaaaa1";
    static final String NO_NAME_ID = "i-0aaaaaaaaaaaaaaa2";
    static final String HERE_ID = "i-0bbbbbbbbbbbbbbb1";
    static final String ELSEWHERE_ID = "i-0bbbbbbbbbbbbbbb2";
    static final String DISCOVERED_ID = "i-0ccccccccccccccc1";
    static final String REMOVED_ID = "i-0ccccccccccccccc2";
    static final String STALE_ID = "i-0ccccccccccccccc3";

    private final ObjectMapper json = new ObjectMapper();
    private Environment env;

    static Ec2InstanceInfo instance(String id, String name, String state) {
        Map<String, String> tags = new HashMap<>();
        if (name != null) {
            tags.put("Name", name);
        }
        return new Ec2InstanceInfo(id, "t3.medium", state, "10.0.1.15", "13.232.1.1", "ip-10-0-1-15.internal",
                "ec2-13-232-1-1.amazonaws.com", "vpc-0abc", "subnet-0abc", "ap-south-1a", "ami-0abc", "my-key",
                Instant.parse("2026-01-01T00:00:00Z"), "linux", "x86_64", "ebs", "/dev/xvda", "hvm", "nitro",
                "arn:aws:iam::123456789012:instance-profile/App", List.of("sg-0abc"), List.of("app-sg"),
                tags, false, "disabled", "default", 1, 2, REGION);
    }

    private Vm registered(VmGroup group, String name, String providerVmId) {
        Vm vm = newVm(group, name, VmStatus.STOPPED);
        vm.setProviderVmId(providerVmId);
        return vmRepository.saveAndFlush(vm);
    }

    @BeforeEach
    void seed() {
        env = newEnvironment("Analytics QA");
        VmGroup web = newGroup(env, "web");
        registered(web, "here", HERE_ID);
        registered(web, "gone", STALE_ID);
        Vm removed = registered(web, "removed", REMOVED_ID);
        removed.setIsActive(false);
        vmRepository.saveAndFlush(removed);
        VmGroup discovered = newGroup(env, "discovered");
        Vm found = registered(discovered, "found", DISCOVERED_ID);
        found.setDiscoveryPending(true);
        vmRepository.saveAndFlush(found);

        Environment other = newEnvironment("CRM UAT");
        registered(newGroup(other, "app"), "elsewhere", ELSEWHERE_ID);

        when(ec2Service.listInstances(any(), eq(REGION))).thenReturn(List.of(
                instance(FREE_ID, "web-03", "running"), instance(NO_NAME_ID, null, "stopped"),
                instance(HERE_ID, "here", "running"), instance(ELSEWHERE_ID, "elsewhere", "running"),
                instance(DISCOVERED_ID, "found", "running"), instance(REMOVED_ID, "removed", "stopped"),
                instance(STALE_ID, "gone", "terminated")));
    }

    private JsonNode candidates(RequestPostProcessor who, String source) throws Exception {
        String body = mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/vms/candidates")
                        .param("region", REGION).param("source", source).with(who))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private static JsonNode byName(JsonNode list, String name) {
        for (JsonNode c : list) {
            if (name == null ? c.get("name").isNull() : name.equals(c.get("name").asText())) {
                return c;
            }
        }
        throw new AssertionError("no candidate named " + name);
    }

    @Test
    void eachInstanceSaysWhatItIsToTheEnvironment() throws Exception {
        JsonNode list = candidates(asEnvAdmin(), "EC2");

        assertThat(list).hasSize(7);
        assertThat(byName(list, "web-03").get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(byName(list, "web-03").get("note").isNull()).isTrue();
        assertThat(byName(list, null).get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(byName(list, "here").get("status").asText()).isEqualTo("IN_ENVIRONMENT");
        assertThat(byName(list, "here").get("note").asText()).isEqualTo("Already in Analytics QA");
        assertThat(byName(list, "here").get("inGroupName").asText()).isEqualTo("web");
        assertThat(byName(list, "elsewhere").get("status").asText()).isEqualTo("IN_ENVIRONMENT");
        assertThat(byName(list, "elsewhere").get("inEnvironmentName").asText()).isEqualTo("CRM UAT");
        assertThat(byName(list, "found").get("status").asText()).isEqualTo("DISCOVERED_UNGROUPED");
        assertThat(byName(list, "found").get("note").asText()).isEqualTo("Found by discovery, not in a group");
        assertThat(byName(list, "removed").get("status").asText()).isEqualTo("REMOVED");
        assertThat(byName(list, "gone").get("status").asText()).isEqualTo("STALE");
        assertThat(byName(list, "gone").get("note").asText()).isEqualTo("In Analytics QA, but AWS reports it terminated");
    }

    @Test
    void everyEnabledRegionListsTheAccountsInstancesWithTheSameStatuses() throws Exception {
        // U19-T02: region ALL sweeps every enabled region of the account (here: aws.region, as no AWS answers);
        // the listing runs in the sweep's threads, the statuses are built in the request (its user and session).
        String body = mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/vms/candidates")
                        .param("region", "ALL").param("source", "EC2").with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode list = json.readTree(body);

        assertThat(list).hasSize(7);
        assertThat(byName(list, "here").get("status").asText()).isEqualTo("IN_ENVIRONMENT");
        assertThat(byName(list, "found").get("status").asText()).isEqualTo("DISCOVERED_UNGROUPED");
        assertThat(byName(list, "web-03").get("zone").asText()).isEqualTo("ap-south-1a");
        list.forEach(c -> assertThat(c.get("ref").asText()).isNotBlank());
        assertThat(list.toString()).doesNotContain("i-0");
    }

    @Test
    void anEnvironmentAdminGetsNoInstanceIds() throws Exception {
        JsonNode list = candidates(asEnvAdmin(), "EC2");

        assertThat(list.toString()).doesNotContain("i-0").doesNotContain("vpc-").doesNotContain("ami-")
                .doesNotContain("sg-0abc").doesNotContain("arn:aws");
        list.forEach(c -> assertThat(c.get("ref").asText()).isNotBlank());
    }

    @Test
    void anAdministratorAlsoGetsTheIds() throws Exception {
        assertThat(byName(candidates(asAdmin(), "EC2"), "web-03").get("providerVmId").asText()).isEqualTo(FREE_ID);
    }

    @Test
    void operatorsAndViewersCannotList() throws Exception {
        for (RequestPostProcessor who : List.of(asUser(), asViewer())) {
            mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/vms/candidates")
                            .param("region", REGION).with(who))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void rdsDatabasesComeThroughTheSameList() throws Exception {
        when(rdsCloudProviderService.isEnabled()).thenReturn(true);
        Vm db = newVm(newGroup(env, "data"), "orders", VmStatus.STOPPED);
        db.setProvider(CloudProvider.AWS_RDS);
        db.setProviderVmId("db:orders");
        vmRepository.saveAndFlush(db);
        when(rdsCloudProviderService.listDatabases(any())).thenReturn(List.of(
                new RdsDatabaseInfoDTO("db:reports", "reports", "instance", "postgres", "16", "db.t3.medium",
                        "available", false, true, null, false),
                new RdsDatabaseInfoDTO("db:replica", "replica", "instance", "postgres", "16", "db.t3.medium",
                        "available", false, false, "Read replicas cannot be stopped", false),
                new RdsDatabaseInfoDTO("db:orders", "orders", "instance", "postgres", "16", "db.t3.medium",
                        "stopped", false, true, null, false)));

        JsonNode list = candidates(asEnvAdmin(), "RDS");

        assertThat(byName(list, "reports").get("status").asText()).isEqualTo("AVAILABLE");
        assertThat(byName(list, "reports").get("instanceType").asText()).isEqualTo("db.t3.medium");
        assertThat(byName(list, "replica").get("note").asText()).isEqualTo("Read replicas cannot be stopped");
        assertThat(byName(list, "orders").get("status").asText()).isEqualTo("IN_ENVIRONMENT");
        assertThat(list.toString()).doesNotContain("db:");
    }

    @Test
    void rdsIsNotFoundWhileItIsOff() throws Exception {
        when(rdsCloudProviderService.isEnabled()).thenReturn(false);
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/vms/candidates")
                        .param("region", REGION).param("source", "RDS").with(asEnvAdmin()))
                .andExpect(status().isNotFound());
    }
}
