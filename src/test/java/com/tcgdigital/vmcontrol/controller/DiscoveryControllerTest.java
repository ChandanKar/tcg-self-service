package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** U19-T03: Found in AWS is for administrators; an ignored cluster leaves the inbox and comes back when brought back. */
class DiscoveryControllerTest extends SecuredWebTestBase {

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void aws() {
        when(eksCloudProviderService.listClusters(any())).thenReturn(List.of("orphan-cluster"));
        when(ec2Service.listInstances(anyString(), anyString())).thenReturn(List.of());
    }

    private JsonNode found() throws Exception {
        return json.readTree(mockMvc.perform(get("/api/v1/discovery/found").param("refresh", "true").with(asAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void onlyAdministratorsSeeIt() throws Exception {
        mockMvc.perform(get("/api/v1/discovery/found").with(asEnvAdmin())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/discovery/found").with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/discovery/ignored").with(asViewer())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/discovery/found").with(asAdmin())).andExpect(status().isOk());
    }

    @Test
    void anIgnoredClusterLeavesTheInboxUntilItIsBroughtBack() throws Exception {
        JsonNode before = found();
        JsonNode cluster = null;
        for (JsonNode item : before.get("items")) {
            if ("orphan-cluster".equals(item.get("name").asText())) cluster = item;
        }
        assertThat(cluster).isNotNull();
        assertThat(cluster.get("kind").asText()).isEqualTo("EKS");

        mockMvc.perform(post("/api/v1/discovery/ignored").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(java.util.Map.of("kind", "EKS", "accountRef", cluster.get("accountRef").asText(),
                                "region", cluster.get("region").asText(), "clusterName", "orphan-cluster"))))
                .andExpect(status().isNoContent());

        assertThat(found().get("items").toString()).doesNotContain("orphan-cluster");
        JsonNode ignored = json.readTree(mockMvc.perform(get("/api/v1/discovery/ignored").with(asAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(ignored).hasSize(1);

        mockMvc.perform(delete("/api/v1/discovery/ignored/" + ignored.get(0).get("id").asText()).with(asAdmin()))
                .andExpect(status().isNoContent());
        assertThat(found().get("items").toString()).contains("orphan-cluster");
    }
}
