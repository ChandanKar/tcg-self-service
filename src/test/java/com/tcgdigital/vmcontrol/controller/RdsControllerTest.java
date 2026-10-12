package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.dto.RdsDatabaseInfoDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.CloudTarget;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Browsing and registering RDS databases (E33-T02): who may, the switch, the registered mark, id checks. */
class RdsControllerTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/rds/databases?region=ap-south-1";

    @Autowired private VmRepository vmRepository;

    private static RdsDatabaseInfoDTO db(String providerVmId, String kind, boolean stoppable) {
        String identifier = providerVmId.substring(providerVmId.indexOf(':') + 1);
        return new RdsDatabaseInfoDTO(providerVmId, identifier, kind, "postgres", "16.3", "db.t3.medium", "available",
                false, stoppable, stoppable ? null : "a read replica", false);
    }

    @BeforeEach
    void rdsOn() {
        when(rdsCloudProviderService.isEnabled()).thenReturn(true);
    }

    @Test
    void envAdminsListDatabasesWithTheRegisteredOnesMarked() throws Exception {
        Environment env = newEnvironment("Rds browse");
        VmGroup group = newGroup(env, "data");
        Vm existing = new Vm();
        existing.setVmId(java.util.UUID.randomUUID().toString());
        existing.setGroup(group);
        existing.setName("orders-db");
        existing.setDisplayName("Orders DB");
        existing.setProvider(CloudProvider.AWS_RDS);
        existing.setRegion("ap-south-1");
        existing.setProviderVmId("db:orders");
        existing.setSequencePosition(1);
        existing.setIsActive(true);
        vmRepository.save(existing);
        when(rdsCloudProviderService.listDatabases(any(CloudTarget.class)))
                .thenReturn(List.of(db("cluster:aurora-qa", "CLUSTER", true), db("db:orders", "INSTANCE", true),
                        db("db:replica", "INSTANCE", false)));

        mockMvc.perform(get(URL + "&accountRef=member-1").with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].kind").value("CLUSTER"))
                .andExpect(jsonPath("$[0].registered").value(false))
                .andExpect(jsonPath("$[1].registered").value(true))
                .andExpect(jsonPath("$[2].stoppable").value(false))
                .andExpect(jsonPath("$[2].notStoppableReason").value("a read replica"));
        verify(rdsCloudProviderService).listDatabases(CloudTarget.of("member-1", "ap-south-1"));
    }

    @Test
    void usersAndViewersMayNotBrowse() throws Exception {
        mockMvc.perform(get(URL).with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).with(asViewer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/rds").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keepStopped").value(false));
    }

    @Test
    void switchedOffBrowsingIsNotFound() throws Exception {
        when(rdsCloudProviderService.isEnabled()).thenReturn(false);

        expectError(mockMvc.perform(get(URL).with(asAdmin())), 404);
        mockMvc.perform(get("/api/v1/rds").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void anRdsVmNeedsAnInstanceOrClusterId() throws Exception {
        Environment env = newEnvironment("Rds register");
        VmGroup group = newGroup(env, "data");
        String body = "{\"groupId\":\"" + group.getGroupId() + "\",\"name\":\"%s\",\"displayName\":\"Orders DB\","
                + "\"provider\":\"AWS_RDS\",\"region\":\"ap-south-1\",\"providerVmId\":\"%s\",\"sequencePosition\":%d}";
        String url = "/api/v1/environments/" + env.getEnvironmentId() + "/vms";

        expectError(mockMvc.perform(post(url).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content(String.format(body, "orders-db", "orders", 1))), 400)
                .andExpect(jsonPath("$.message", containsString("db:<instance identifier>")));
        mockMvc.perform(post(url).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(String.format(body, "aurora-qa", "cluster:aurora-qa", 1)))
                .andExpect(status().is2xxSuccessful());

        Vm saved = vmRepository.findByProviderAndProviderVmId(CloudProvider.AWS_RDS, "cluster:aurora-qa").orElseThrow();
        assertThat(saved.getAwsAccountRef()).isNotNull();
    }
}
