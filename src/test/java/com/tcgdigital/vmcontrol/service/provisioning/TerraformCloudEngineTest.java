package com.tcgdigital.vmcontrol.service.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.EnvironmentTemplate;
import com.tcgdigital.vmcontrol.model.ProvisioningEngineType;
import com.tcgdigital.vmcontrol.model.ProvisioningRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Terraform engine's calls to the HCP Terraform runs API and its status mapping (E32-T06). */
class TerraformCloudEngineTest {

    private static final String BASE = "https://tfe.example.com";

    private MockRestServiceServer server;
    private TerraformCloudEngine engine;
    private EnvironmentTemplate template;
    private ProvisioningRequest request;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        engine = new TerraformCloudEngine(builder.build(), new ObjectMapper(), "default-org", true);
        template = new EnvironmentTemplate();
        template.setEngine(ProvisioningEngineType.TERRAFORM);
        template.setEngineRef("{\"organization\":\"tcg\",\"workspacePrefix\":\"dev-\",\"vcsRepoIdentifier\":\"tcg/envs\","
                + "\"oauthTokenId\":\"ot-1\",\"terraformVersion\":\"1.9.5\"}");
        request = new ProvisioningRequest();
        request.setRequestId("req-1");
        request.setEnvironmentName("Feature X");
        request.setParameters("{\"instance_type\":\"t3.small\"}");
    }

    private static String json(String s) {
        return s.replace('\'', '"');
    }

    @Test
    void provisioningCreatesTheWorkspaceSetsVariablesAndQueuesAnAppliedRun() {
        server.expect(requestTo(BASE + "/api/v2/organizations/tcg/workspaces/dev-feature-x")).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE + "/api/v2/organizations/tcg/workspaces")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.attributes.name").value("dev-feature-x"))
                .andExpect(jsonPath("$.data.attributes.auto-apply").value(true))
                .andExpect(jsonPath("$.data.attributes.terraform-version").value("1.9.5"))
                .andExpect(jsonPath("$.data.attributes.vcs-repo.identifier").value("tcg/envs"))
                .andExpect(jsonPath("$.data.attributes.tag-names[1]").value("provisioning-request-req-1"))
                .andRespond(withSuccess(json("{'data':{'id':'ws-1'}}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v2/workspaces/ws-1/vars")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json("{'data':[{'id':'var-9','attributes':{'key':'environment_name'}}]}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v2/workspaces/ws-1/vars")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.attributes.key").value("instance_type"))
                .andExpect(jsonPath("$.data.attributes.value").value("t3.small"))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE + "/api/v2/workspaces/ws-1/vars/var-9")).andExpect(method(HttpMethod.PATCH))
                .andExpect(jsonPath("$.data.attributes.value").value("feature-x"))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE + "/api/v2/workspaces/ws-1/vars")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.attributes.key").value("provisioning_request_id"))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE + "/api/v2/workspaces/ws-1/vars")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.attributes.key").value("tags"))
                .andExpect(jsonPath("$.data.attributes.hcl").value(true))
                .andRespond(withSuccess());
        server.expect(requestTo(BASE + "/api/v2/runs")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.attributes.is-destroy").value(false))
                .andExpect(jsonPath("$.data.relationships.workspace.data.id").value("ws-1"))
                .andRespond(withSuccess(json("{'data':{'id':'run-1'}}"), MediaType.APPLICATION_JSON));

        assertThat(engine.provision(request, template)).isEqualTo("run-1");
        server.verify();
    }

    private void run(String status, boolean destroy) {
        server.expect(requestTo(BASE + "/api/v2/runs/run-1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json("{'data':{'id':'run-1','attributes':{'status':'" + status + "','is-destroy':" + destroy
                        + "},'relationships':{'workspace':{'data':{'id':'ws-1'}}}}}"), MediaType.APPLICATION_JSON));
    }

    @Test
    void runStatusesMapToProgress() {
        run("planning", false);
        assertThat(engine.poll(template, "run-1").state()).isEqualTo(ProvisioningProgress.State.IN_PROGRESS);
        server.reset();
        run("errored", false);
        ProvisioningProgress failed = engine.poll(template, "run-1");
        assertThat(failed.state()).isEqualTo(ProvisioningProgress.State.FAILED);
        assertThat(failed.message()).isEqualTo("Terraform run errored");
        server.reset();
        run("applied", false);
        ProvisioningProgress done = engine.poll(template, "run-1");
        assertThat(done.state()).isEqualTo(ProvisioningProgress.State.SUCCEEDED);
        assertThat(done.resourceId()).isEqualTo("ws-1");
    }

    @Test
    void anAppliedDestroyRunDeletesTheWorkspaceWhenConfigured() {
        run("applied", true);
        server.expect(requestTo(BASE + "/api/v2/workspaces/ws-1")).andExpect(method(HttpMethod.DELETE)).andRespond(withSuccess());

        assertThat(engine.poll(template, "run-1").state()).isEqualTo(ProvisioningProgress.State.SUCCEEDED);
        server.verify();
    }

    @Test
    void destroyQueuesADestroyRunOnTheWorkspaceAndOutputsSkipSensitiveValues() {
        request.setEngineResourceId("ws-1");
        server.expect(requestTo(BASE + "/api/v2/runs")).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.data.attributes.is-destroy").value(true))
                .andRespond(withSuccess(json("{'data':{'id':'run-d'}}"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/api/v2/workspaces/ws-1/current-state-version-outputs")).andRespond(withSuccess(
                json("{'data':[{'attributes':{'name':'cluster','value':'dev-a','sensitive':false}},"
                        + "{'attributes':{'name':'password','value':'x','sensitive':true}}]}"), MediaType.APPLICATION_JSON));

        assertThat(engine.destroy(request, template)).isEqualTo("run-d");
        assertThat(engine.outputs(template, "ws-1")).containsOnlyKeys("cluster").containsEntry("cluster", "dev-a");
        server.verify();
    }

    @Test
    void workspaceNamesAreSafe() {
        assertThat(TerraformCloudEngine.workspaceName("dev-", "Team A/Feature")).isEqualTo("dev-team-a-feature");
    }
}
