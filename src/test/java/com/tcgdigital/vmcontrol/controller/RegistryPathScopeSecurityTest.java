package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Group and VM registry endpoints act only on resources in the {environmentId} of the path,
 * and environment-admin mutations follow security.env-admin.scope (E03-T04).
 */
class RegistryPathScopeSecurityTest extends SecuredWebTestBase {

    @Autowired
    private SecurityService securityService;

    private Object originalScope;
    private Environment envA;
    private Environment envB;
    private VmGroup groupA;
    private VmGroup groupB;
    private Vm vmB;

    @BeforeEach
    void setUpEnvironments() {
        originalScope = ReflectionTestUtils.getField(securityService, "envAdminScope");
        envA = newEnvironment("Env A");
        envB = newEnvironment("Env B");
        groupA = newGroup(envA, "web-a");
        groupB = newGroup(envB, "web-b");
        vmB = newVm(groupB, "vm-b", VmStatus.STOPPED);
    }

    @AfterEach
    void restoreScope() {
        ReflectionTestUtils.setField(securityService, "envAdminScope", originalScope);
    }

    private String envA(String suffix) {
        return "/api/v1/environments/" + envA.getEnvironmentId() + suffix;
    }

    private static String groupBody(String name) {
        return "{\"name\":\"" + name + "\",\"displayName\":\"" + name + "\",\"sequencePosition\":5}";
    }

    @Test
    void groupFromAnotherEnvironmentIs404AndUnchanged() throws Exception {
        expectError(mockMvc.perform(put(envA("/groups/" + groupB.getGroupId())).with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(groupBody("renamed"))), 404);
        expectError(mockMvc.perform(get(envA("/groups/" + groupB.getGroupId())).with(asAdmin())), 404);
        expectError(mockMvc.perform(delete(envA("/groups/" + groupB.getGroupId())).with(asAdmin())), 404);

        assertThat(vmGroupRepository.findById(groupB.getGroupId()).orElseThrow().getName()).isEqualTo(groupB.getName());
    }

    @Test
    void vmFromAnotherEnvironmentIs404AndUnchanged() throws Exception {
        expectError(mockMvc.perform(get(envA("/vms/" + vmB.getVmId())).with(asAdmin())), 404);
        expectError(mockMvc.perform(put(envA("/vms/" + vmB.getVmId() + "/acknowledge")).with(asAdmin())), 404);
        expectError(mockMvc.perform(delete(envA("/vms/" + vmB.getVmId())).with(asAdmin())), 404);
        expectError(mockMvc.perform(get(envA("/vms/" + groupB.getGroupId() + "/page")).with(asAdmin())), 404);

        assertThat(vmRepository.findById(vmB.getVmId())).isPresent();
    }

    @Test
    void registeringIntoAnotherEnvironmentsGroupIs404() throws Exception {
        String body = "{\"groupId\":\"" + groupB.getGroupId() + "\",\"name\":\"intruder\",\"displayName\":\"Intruder\","
                + "\"provider\":\"AWS\",\"region\":\"us-east-1\",\"providerVmId\":\"i-0abcdef0123456789\","
                + "\"sequencePosition\":1}";

        expectError(mockMvc.perform(post(envA("/vms")).with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(body)), 404);
        assertThat(vmRepository.findAll()).noneMatch(v -> "intruder".equals(v.getName()));
    }

    @Test
    void matchingEnvironmentStillWorks() throws Exception {
        mockMvc.perform(get(envA("/groups/" + groupA.getGroupId())).with(asAdmin())).andExpect(status().isOk());
        mockMvc.perform(put(envA("/groups/" + groupA.getGroupId())).with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(groupBody(groupA.getName()))) // names are immutable (E10-T03)
                .andExpect(status().isOk());
    }

    @Test
    void assignedScopeEnvAdminNeedsAnAdminGrantToChangeTheRegistry() throws Exception {
        ReflectionTestUtils.setField(securityService, "envAdminScope", "assigned");

        expectError(mockMvc.perform(post(envA("/groups")).with(asEnvAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(groupBody("new-group"))), 403);

        grantEnv(envAdmin, envA.getEnvironmentId(), AccessLevel.ADMIN);
        mockMvc.perform(post(envA("/groups")).with(asEnvAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(groupBody("new-group")))
                .andExpect(status().isCreated());
    }

    @Test
    void globalScopeEnvAdminChangesAnyEnvironment() throws Exception {
        ReflectionTestUtils.setField(securityService, "envAdminScope", "global");

        mockMvc.perform(post(envA("/groups")).with(asEnvAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(groupBody("by-env-admin")))
                .andExpect(status().isCreated());
    }
}
