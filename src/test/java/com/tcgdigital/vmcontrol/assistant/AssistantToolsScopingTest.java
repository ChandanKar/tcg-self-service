package com.tcgdigital.vmcontrol.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.controller.McpController;
import com.tcgdigital.vmcontrol.dto.CreateApiTokenDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.ApiTokenService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The assistant's tools (E36-T02) show only what the token's user may see; another environment is "not found". */
class AssistantToolsScopingTest extends SecuredWebTestBase {

    @Autowired private ApiTokenService tokenService;
    @Autowired private McpController mcpController;
    @Autowired private ObjectMapper objectMapper;

    private Environment mine;
    private Environment theirs;
    private int id;

    private void switches(boolean on) {
        Object tokenTarget = AopTestUtils.getTargetObject(tokenService);
        ReflectionTestUtils.setField(tokenTarget, "enabled", on);
        Object mcpTarget = AopTestUtils.getTargetObject(mcpController);
        ReflectionTestUtils.setField(mcpTarget, "enabled", on);
    }

    @BeforeEach
    void setUp() {
        switches(true);
        mine = newEnvironment("Assist Mine");
        theirs = newEnvironment("Assist Theirs");
        newGroup(mine, "web");
        newGroup(theirs, "web");
        grantEnv(operator, mine.getEnvironmentId(), AccessLevel.VIEWER);
    }

    @AfterEach
    void off() {
        switches(false);
    }

    private String token(User user) {
        return tokenService.create(user, new CreateApiTokenDTO("assistant", List.of("assistant:read"), 1)).token();
    }

    private JsonNode call(String token, String tool, Map<String, Object> arguments) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("jsonrpc", "2.0", "id", ++id, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", arguments)));
        String response = mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("result");
    }

    private static List<String> names(JsonNode rows, String field) {
        List<String> names = new ArrayList<>();
        rows.forEach(r -> names.add(r.path(field).asText()));
        return names;
    }

    @Test
    void everyReadToolIsListed() throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        JsonNode tools = objectMapper.readTree(mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Authorization", "Bearer " + token(operator))).andReturn().getResponse().getContentAsString()).path("result").path("tools");

        assertThat(names(tools, "name")).contains("whoami", "list_environments", "get_environment", "get_environment_cost",
                "spend_by", "cost_trend", "savings_summary", "idle_resources", "rightsizing_candidates");
        tools.forEach(t -> assertThat(t.path("inputSchema").path("type").asText()).isEqualTo("object"));
    }

    @Test
    void aUserSeesOnlyTheirEnvironments() throws Exception {
        String token = token(operator);

        JsonNode list = call(token, "list_environments", Map.of());
        assertThat(list.path("isError").asBoolean()).isFalse();
        assertThat(names(list.path("structuredContent").path("environments"), "id"))
                .contains(mine.getEnvironmentId()).doesNotContain(theirs.getEnvironmentId());

        JsonNode own = call(token, "get_environment", Map.of("environmentId", mine.getEnvironmentId()));
        assertThat(own.path("isError").asBoolean()).isFalse();
        assertThat(own.path("structuredContent").path("groups").asInt()).isEqualTo(1);

        for (String tool : List.of("get_environment", "get_environment_cost", "cost_trend", "savings_summary",
                "idle_resources", "rightsizing_candidates")) {
            JsonNode other = call(token, tool, Map.of("environmentId", theirs.getEnvironmentId()));
            assertThat(other.path("isError").asBoolean()).as(tool).isTrue();
            assertThat(other.path("content").get(0).path("text").asText()).as(tool).contains("not found");
        }

        JsonNode spend = call(token, "spend_by", Map.of("dimension", "environment"));
        assertThat(spend.path("isError").asBoolean()).isFalse();
        assertThat(names(spend.path("structuredContent").path("rows"), "environment")).doesNotContain(theirs.getName());
        assertThat(spend.path("structuredContent").path("basis").asText()).contains("estimated");
    }

    @Test
    void anAdminSeesEveryEnvironment() throws Exception {
        JsonNode list = call(token(admin), "list_environments", Map.of("search", "assist"));

        assertThat(names(list.path("structuredContent").path("environments"), "id"))
                .contains(mine.getEnvironmentId(), theirs.getEnvironmentId());
        JsonNode cost = call(token(admin), "get_environment_cost", Map.of("environmentId", theirs.getEnvironmentId()));
        assertThat(cost.path("isError").asBoolean()).isFalse();
        assertThat(cost.path("structuredContent").path("currency").asText()).isEqualTo("USD");
    }

    @Test
    void badArgumentsAreToolErrorsNotFailures() throws Exception {
        String token = token(operator);

        assertThat(call(token, "spend_by", Map.of("dimension", "planet")).path("isError").asBoolean()).isTrue();
        assertThat(call(token, "savings_summary", Map.of("month", "2999-01")).path("isError").asBoolean()).isTrue();
        assertThat(call(token, "savings_summary", Map.of("month", "soon")).path("isError").asBoolean()).isTrue();
        assertThat(call(token, "rightsizing_candidates", Map.of("direction", "SIDEWAYS")).path("isError").asBoolean()).isTrue();
        assertThat(call(token, "cost_trend", Map.of("days", 400)).path("isError").asBoolean()).isTrue();
        assertThat(call(token, "get_environment", Map.of()).path("isError").asBoolean()).isTrue();

        JsonNode savings = call(token, "savings_summary", Map.of());
        assertThat(savings.path("isError").asBoolean()).isFalse();
        assertThat(savings.path("structuredContent").path("currency").asText()).isEqualTo("USD");
        JsonNode idle = call(token, "idle_resources", Map.of("limit", 500));
        assertThat(idle.path("isError").asBoolean()).isFalse();
        assertThat(idle.path("structuredContent").path("idle").size()).isLessThanOrEqualTo(50);
    }
}
