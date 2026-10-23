package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static com.tcgdigital.vmcontrol.controller.VmCandidatesControllerTest.instance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U11-T01: several picked instances go into one group after its current VMs, or into a new group at
 * the end of the start order; all or nothing.
 */
class VmBatchRegisterIntegrationTest extends SecuredWebTestBase {

    private static final String REGION = VmCandidatesControllerTest.REGION;
    private static final String A_ID = "i-0aaaaaaaaaaaaaaa1";
    private static final String B_ID = "i-0aaaaaaaaaaaaaaa2";
    private static final String ELSEWHERE_ID = "i-0bbbbbbbbbbbbbbb2";
    private static final String DISCOVERED_ID = "i-0ccccccccccccccc1";

    private final ObjectMapper json = new ObjectMapper();
    private Environment env;
    private VmGroup web;
    private VmGroup discoveryGroup;
    private Vm discovered;

    private Vm vm(VmGroup group, String name, String providerVmId, int position) {
        Vm vm = newVm(group, name, VmStatus.STOPPED);
        vm.setProviderVmId(providerVmId);
        vm.setSequencePosition(position);
        return vmRepository.saveAndFlush(vm);
    }

    @BeforeEach
    void seed() {
        env = newEnvironment("Analytics QA");
        web = newGroup(env, "Web");
        web.setSequencePosition(1);
        web = vmGroupRepository.saveAndFlush(web);
        vm(web, "web-01", "i-0ddddddddddddddd1", 1);
        vm(web, "web-02", "i-0ddddddddddddddd2", 2);
        discoveryGroup = newGroup(env, "Auto-Discovered");
        discoveryGroup.setSequencePosition(999);
        discoveryGroup = vmGroupRepository.saveAndFlush(discoveryGroup);
        discovered = vm(discoveryGroup, "found", DISCOVERED_ID, 1);
        discovered.setDiscoveryPending(true);
        discovered = vmRepository.saveAndFlush(discovered);

        Environment other = newEnvironment("CRM UAT");
        vm(newGroup(other, "app"), "elsewhere", ELSEWHERE_ID, 1);

        when(ec2Service.listInstances(any(), eq(REGION))).thenReturn(List.of(
                instance(A_ID, "web-03", "running"), instance(B_ID, "web-04", "stopped"),
                instance(ELSEWHERE_ID, "elsewhere", "running"), instance(DISCOVERED_ID, "found", "running")));
    }

    /** The reference of the named candidate, as the given user sees the list. */
    private String ref(RequestPostProcessor who, String name) throws Exception {
        JsonNode list = json.readTree(mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/vms/candidates")
                        .param("region", REGION).with(who))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode c : list) {
            if (name.equals(c.get("name").asText())) {
                return c.get("ref").asText();
            }
        }
        throw new AssertionError("no candidate " + name);
    }

    private ResultActions batch(RequestPostProcessor who, String group, String... refAndNames) throws Exception {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < refAndNames.length; i += 2) {
            items.append(i == 0 ? "" : ",").append("{\"ref\":\"").append(refAndNames[i])
                    .append("\",\"name\":\"").append(refAndNames[i + 1]).append("\"}");
        }
        return mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/vms/batch").with(who)
                .contentType(MediaType.APPLICATION_JSON).content("{" + group + ",\"items\":[" + items + "]}"));
    }

    private String inWeb() {
        return "\"groupId\":\"" + web.getGroupId() + "\"";
    }

    private Vm byProviderId(String id) {
        return vmRepository.findAll().stream().filter(v -> id.equals(v.getProviderVmId())).findFirst().orElse(null);
    }

    @Test
    void twoInstancesGoAfterTheGroupsCurrentVms() throws Exception {
        batch(asEnvAdmin(), inWeb(), ref(asEnvAdmin(), "web-03"), "web-03", ref(asEnvAdmin(), "web-04"), "web-04")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].providerVmId").doesNotExist());

        Vm a = byProviderId(A_ID);
        Vm b = byProviderId(B_ID);
        assertThat(a.getGroup().getGroupId()).isEqualTo(web.getGroupId());
        assertThat(a.getSequencePosition()).isEqualTo(3);
        assertThat(b.getSequencePosition()).isEqualTo(4);
        assertThat(a.getDisplayName()).isEqualTo("web-03");
        assertThat(a.getRegion()).isEqualTo(REGION);
    }

    @Test
    void aNewGroupGoesAtTheEndOfTheStartOrderBeforeAutoDiscovered() throws Exception {
        batch(asEnvAdmin(), "\"newGroupName\":\"Reports\"", ref(asEnvAdmin(), "web-03"), "web-03")
                .andExpect(status().isCreated());

        List<VmGroup> groups = vmGroupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(env.getEnvironmentId());
        assertThat(groups).extracting(VmGroup::getDisplayName).containsExactly("Web", "Reports", "Auto-Discovered");
        VmGroup reports = groups.get(1);
        assertThat(reports.getSequencePosition()).isEqualTo(2);
        assertThat(vmRepository.findByGroupId(reports.getGroupId())).singleElement()
                .satisfies(vm -> {
                    assertThat(vm.getProviderVmId()).isEqualTo(A_ID);
                    assertThat(vm.getSequencePosition()).isEqualTo(1);
                });
    }

    @Test
    void anInstanceAlreadyInAnEnvironmentRefusesTheWholeBatch() throws Exception {
        long before = vmRepository.count();

        batch(asEnvAdmin(), inWeb(), ref(asEnvAdmin(), "web-03"), "web-03", ref(asEnvAdmin(), "elsewhere"), "elsewhere")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("elsewhere is already in CRM UAT. Nothing was added."));

        assertThat(vmRepository.count()).isEqualTo(before);
        assertThat(byProviderId(A_ID)).isNull();
    }

    @Test
    void aDiscoveredVmIsMovedIntoTheGroup() throws Exception {
        long before = vmRepository.count();

        batch(asEnvAdmin(), inWeb(), ref(asEnvAdmin(), "found"), "found").andExpect(status().isCreated());

        Vm moved = vmRepository.findById(discovered.getVmId()).orElseThrow();
        assertThat(moved.getGroup().getGroupId()).isEqualTo(web.getGroupId());
        assertThat(moved.getSequencePosition()).isEqualTo(3);
        assertThat(moved.getDiscoveryPending()).isFalse();
        assertThat(vmRepository.count()).isEqualTo(before);
    }

    @Test
    void aFailureHalfwayWritesNothing() throws Exception {
        // The second VM's name is taken in Web: the first one must not stay behind.
        batch(asEnvAdmin(), inWeb(), ref(asEnvAdmin(), "web-03"), "web-03", ref(asEnvAdmin(), "web-04"),
                vmRepository.findAll().stream().filter(v -> "web-01".equals(v.getDisplayName())).findFirst().orElseThrow().getName())
                .andExpect(status().isBadRequest());

        assertThat(byProviderId(A_ID)).isNull();
        assertThat(byProviderId(B_ID)).isNull();
    }

    @Test
    void anEksWorkerNodeIsShownButNeverAdded() throws Exception {
        var node = instance("i-0eeeeeeeeeeeeeee1", "shop-ng-a", "running");
        node.tags().put("eks:cluster-name", "shop");
        when(ec2Service.listInstances(any(), eq(REGION))).thenReturn(List.of(instance(A_ID, "web-03", "running"), node));
        JsonNode list = json.readTree(mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/vms/candidates")
                        .param("region", REGION).with(asEnvAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode shown = null;
        for (JsonNode c : list) {
            if ("shop-ng-a".equals(c.get("name").asText())) {
                shown = c;
            }
        }
        assertThat(shown).isNotNull();
        assertThat(shown.get("status").asText()).isEqualTo("EKS_NODE");
        assertThat(shown.get("note").asText()).isEqualTo("Node of EKS cluster shop: its node group manages it");

        // A stale or hand-made request is refused too, whole batch.
        batch(asEnvAdmin(), inWeb(), ref(asEnvAdmin(), "web-03"), "web-03", shown.get("ref").asText(), "shop-ng-a")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("shop-ng-a is a node of EKS cluster shop (its node group manages it). Nothing was added."));
        assertThat(byProviderId(A_ID)).isNull();
        assertThat(byProviderId("i-0eeeeeeeeeeeeeee1")).isNull();
    }

    @Test
    void anEksEnvironmentTakesNoVms() throws Exception {
        String ref = ref(asEnvAdmin(), "web-03");
        env.setServiceType("EKS");
        environmentRepository.saveAndFlush(env);

        batch(asEnvAdmin(), inWeb(), ref, "web-03")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Analytics QA is an EKS environment: its node groups come from the cluster, "
                        + "and VMs cannot be added to it. Nothing was added."));
        assertThat(byProviderId(A_ID)).isNull();
    }

    @Test
    void theSameInstanceTwiceIsRefused() throws Exception {
        String ref = ref(asEnvAdmin(), "web-03");
        batch(asEnvAdmin(), inWeb(), ref, "web-03", ref, "web-03b")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("web-03b is picked twice. Nothing was added."));
    }

    @Test
    void aGroupMustBeChosenOnceAndBelongToTheEnvironment() throws Exception {
        String ref = ref(asEnvAdmin(), "web-03");
        batch(asEnvAdmin(), "\"groupId\":null", ref, "web-03")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pick a group, or name a new one"));
        batch(asEnvAdmin(), inWeb() + ",\"newGroupName\":\"Reports\"", ref, "web-03")
                .andExpect(status().isBadRequest());

        VmGroup foreign = newGroup(newEnvironment("Other"), "x");
        batch(asEnvAdmin(), "\"groupId\":\"" + foreign.getGroupId() + "\"", ref, "web-03")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("That group is in another environment"));
    }

    @Test
    void aReferenceIssuedToSomeoneElseIsRefused() throws Exception {
        batch(asEnvAdmin(), inWeb(), ref(asAdmin(), "web-03"), "web-03")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This list is out of date. Fetch the instances again."));
    }

    @Test
    void anEmptyBatchAndOperatorsAreRefused() throws Exception {
        batch(asEnvAdmin(), inWeb()).andExpect(status().isBadRequest());
        batch(asUser(), inWeb(), "x", "web-03").andExpect(status().isForbidden());
    }
}
