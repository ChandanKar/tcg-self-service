package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.model.VmVolumeSnapshot;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmVolumeSnapshotRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U01-T01: AWS instance ids (and EBS volume ids) reach administrators only. IP addresses stay visible to
 * everyone who can view the environment.
 */
class VmProviderIdMaskingTest extends SecuredWebTestBase {

    private static final String INSTANCE_ID = "i-0123456789abcdef0";
    private static final String VOLUME_ID = "vol-0123456789abcdef0";

    @Autowired private VmInventorySnapshotRepository inventoryRepository;
    @Autowired private VmVolumeSnapshotRepository volumeRepository;

    private Environment env;
    private VmGroup group;
    private Vm vm;

    @BeforeEach
    void seed() {
        env = newEnvironment("Masking");
        group = newGroup(env, "app");
        vm = newVm(group, "app-1", VmStatus.RUNNING);
        vm.setProviderVmId(INSTANCE_ID);
        vm = vmRepository.saveAndFlush(vm);

        VmInventorySnapshot snapshot = new VmInventorySnapshot();
        snapshot.setInventoryId(UUID.randomUUID().toString());
        snapshot.setVm(vm);
        snapshot.setProvider(CloudProvider.AWS);
        snapshot.setProviderVmId(INSTANCE_ID);
        snapshot.setInstanceType("t3.medium");
        snapshot.setPrivateIp("10.0.1.15");
        snapshot.setLastRefreshedAt(Timestamp.from(Instant.now()));
        inventoryRepository.saveAndFlush(snapshot);

        VmVolumeSnapshot volume = new VmVolumeSnapshot();
        volume.setVm(vm);
        volume.setVolumeId(VOLUME_ID);
        volume.setDeviceName("/dev/xvda");
        volume.setVolumeType("gp3");
        volume.setSizeGib(30);
        volume.setLastRefreshedAt(Timestamp.from(Instant.now()));
        volumeRepository.saveAndFlush(volume);

        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);
    }

    private String vmsUrl() {
        return "/api/v1/environments/" + env.getEnvironmentId() + "/vms";
    }

    private String body(String url, RequestPostProcessor who) throws Exception {
        return mockMvc.perform(get(url).with(who))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void anAdministratorSeesTheInstanceAndVolumeIds() throws Exception {
        mockMvc.perform(get(vmsUrl() + "/" + vm.getVmId()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerVmId").value(INSTANCE_ID));
        mockMvc.perform(get(vmsUrl() + "/" + vm.getVmId() + "/inventory").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerVmId").value(INSTANCE_ID))
                .andExpect(jsonPath("$.volumes[0].volumeId").value(VOLUME_ID));
    }

    @Test
    void nobodyElseReceivesAnInstanceOrVolumeId() throws Exception {
        for (RequestPostProcessor who : new RequestPostProcessor[]{asEnvAdmin(), asUser(), asViewer()}) {
            String list = body(vmsUrl(), who);
            String page = body(vmsUrl() + "/" + group.getGroupId() + "/page", who);
            String one = body(vmsUrl() + "/" + vm.getVmId(), who);
            String inventory = body(vmsUrl() + "/" + vm.getVmId() + "/inventory", who);

            for (String json : new String[]{list, page, one, inventory}) {
                assertThat(json).doesNotContain(INSTANCE_ID).doesNotContain(VOLUME_ID)
                        .doesNotContain("providerVmId").doesNotContain("volumeId");
            }
            assertThat(one).contains("\"privateIp\":\"10.0.1.15\"");
            assertThat(inventory).contains("\"privateIp\":\"10.0.1.15\"").contains("/dev/xvda");
        }
    }

    @Test
    void anRdsDatabaseIsLabelledWithoutItsIdentifier() throws Exception {
        Vm db = newVm(group, "orders-db", VmStatus.STOPPED);
        db.setProvider(CloudProvider.AWS_RDS);
        db.setProviderVmId("cluster:orders-" + db.getVmId().substring(0, 8));
        vmRepository.saveAndFlush(db);

        mockMvc.perform(get(vmsUrl() + "/" + db.getVmId()).with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rdsKind").value("CLUSTER"))
                .andExpect(jsonPath("$.providerVmId").doesNotExist());
    }

    private String editBody(String providerVmId) {
        return """
                {"groupId":"%s","name":"%s","displayName":"App one","provider":"AWS","region":"ap-south-1",
                 "providerVmId":"%s","sequencePosition":%d}
                """.formatted(group.getGroupId(), vm.getName(), providerVmId, vm.getSequencePosition());
    }

    @Test
    void anEnvironmentAdminsEditKeepsTheInstanceId() throws Exception {
        mockMvc.perform(put(vmsUrl() + "/" + vm.getVmId()).with(asEnvAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(editBody("")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("App one"))
                .andExpect(jsonPath("$.providerVmId").doesNotExist());

        assertThat(vmRepository.findById(vm.getVmId()).orElseThrow().getProviderVmId()).isEqualTo(INSTANCE_ID);
    }

    @Test
    void onlyAnAdministratorCanPointAVmAtAnotherInstance() throws Exception {
        expectError(mockMvc.perform(put(vmsUrl() + "/" + vm.getVmId()).with(asEnvAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(editBody("i-0fedcba9876543210"))), 400);

        mockMvc.perform(put(vmsUrl() + "/" + vm.getVmId()).with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(editBody("i-0fedcba9876543210")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerVmId").value("i-0fedcba9876543210"));
    }
}
