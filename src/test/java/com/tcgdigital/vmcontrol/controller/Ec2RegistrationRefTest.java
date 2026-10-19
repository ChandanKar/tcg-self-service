package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.Ec2InstanceInfo;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U01-T02: environment admins pick EC2 instances by name and register them by an opaque reference; the
 * instance id (and network, image and key ids) reach administrators only.
 */
class Ec2RegistrationRefTest extends SecuredWebTestBase {

    private static final String REGION = "ap-south-1";
    private static final String FREE_ID = "i-0aaaaaaaaaaaaaaa1";
    private static final String TAKEN_ID = "i-0bbbbbbbbbbbbbbb2";

    private final ObjectMapper json = new ObjectMapper();
    private Environment env;
    private VmGroup group;

    private static Ec2InstanceInfo instance(String id, String name, String state) {
        return new Ec2InstanceInfo(id, "t3.medium", state, "10.0.1.15", "13.232.1.1", "ip-10-0-1-15.internal",
                "ec2-13-232-1-1.amazonaws.com", "vpc-0abc", "subnet-0abc", "ap-south-1a", "ami-0abc", "my-key",
                Instant.parse("2026-01-01T00:00:00Z"), "linux", "x86_64", "ebs", "/dev/xvda", "hvm", "nitro",
                "arn:aws:iam::123456789012:instance-profile/App", List.of("sg-0abc"), List.of("app-sg"),
                Map.of("Name", name), false, "disabled", "default", 1, 2, REGION);
    }

    @BeforeEach
    void seed() {
        env = newEnvironment("Register by ref");
        group = newGroup(env, "app");
        Vm taken = newVm(group, "taken", VmStatus.RUNNING);
        taken.setProviderVmId(TAKEN_ID);
        vmRepository.saveAndFlush(taken);
        when(ec2Service.listInstances(any(), eq(REGION))).thenReturn(List.of(
                instance(FREE_ID, "web-03", "running"), instance(TAKEN_ID, "taken", "stopped")));
    }

    private JsonNode list(RequestPostProcessor who) throws Exception {
        String body = mockMvc.perform(get("/api/ec2/instances").param("region", REGION).with(who))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private String register(RequestPostProcessor who, String fields) throws Exception {
        return """
                {"groupId":"%s","name":"web-03","displayName":"Web 03","provider":"AWS","region":"%s","sequencePosition":%d,%s}
                """.formatted(group.getGroupId(), REGION, (int) vmRepository.count() + 1, fields);
    }

    @Test
    void anEnvironmentAdminGetsNamesAndReferencesButNoIds() throws Exception {
        JsonNode choices = list(asEnvAdmin());
        String raw = choices.toString();

        assertThat(raw).doesNotContain(FREE_ID).doesNotContain(TAKEN_ID).doesNotContain("vpc-")
                .doesNotContain("subnet-").doesNotContain("ami-").doesNotContain("arn:aws").doesNotContain("sg-0abc")
                .doesNotContain("my-key");
        assertThat(choices.get(0).get("ref").asText()).isNotBlank();
        assertThat(choices.get(0).get("name").asText()).isEqualTo("web-03");
        assertThat(choices.get(0).get("registered").asBoolean()).isFalse();
        assertThat(choices.get(1).get("registered").asBoolean()).isTrue();
        assertThat(choices.get(1).get("registeredEnvironmentName").asText()).isNotBlank();
    }

    @Test
    void anAdministratorAlsoGetsTheIds() throws Exception {
        assertThat(list(asAdmin()).get(0).get("instanceId").asText()).isEqualTo(FREE_ID);
    }

    @Test
    void anEnvironmentAdminRegistersByReference() throws Exception {
        String ref = list(asEnvAdmin()).get(0).get("ref").asText();

        mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/vms").with(asEnvAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(register(asEnvAdmin(), "\"instanceRef\":\"" + ref + "\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerVmId").doesNotExist());

        Vm created = vmRepository.findAll().stream().filter(v -> "web-03".equals(v.getName())).findFirst().orElseThrow();
        assertThat(created.getProviderVmId()).isEqualTo(FREE_ID);
        assertThat(created.getRegion()).isEqualTo(REGION);
    }

    @Test
    void aReferenceIssuedToSomeoneElseIsRefused() throws Exception {
        String adminsRef = list(asAdmin()).get(0).get("ref").asText();

        mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/vms").with(asEnvAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(register(asEnvAdmin(), "\"instanceRef\":\"" + adminsRef + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This list is out of date. Fetch the instances again."));
    }

    @Test
    void anEnvironmentAdminCannotTypeAnEc2Id() throws Exception {
        mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/vms").with(asEnvAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(register(asEnvAdmin(), "\"providerVmId\":\"" + FREE_ID + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Pick the instance from the AWS list."));
    }

    @Test
    void anAdministratorMayStillTypeAnEc2Id() throws Exception {
        mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/vms").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(register(asAdmin(), "\"providerVmId\":\"" + FREE_ID + "\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerVmId").value(FREE_ID));
    }

    @Test
    void aDuplicateIsReportedWithoutTheId() throws Exception {
        String takenRef = list(asEnvAdmin()).get(1).get("ref").asText();

        String body = mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/vms").with(asEnvAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(register(asEnvAdmin(), "\"instanceRef\":\"" + takenRef + "\"")))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("already registered").doesNotContain(TAKEN_ID);
    }

    @Test
    void theRegisteredIdListIsForAdministratorsOnly() throws Exception {
        mockMvc.perform(get("/api/ec2/registered-ids").with(asEnvAdmin())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/ec2/registered-ids").with(asAdmin())).andExpect(status().isOk());
    }
}
