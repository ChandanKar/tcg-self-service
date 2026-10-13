package com.tcgdigital.vmcontrol.assistant;

import com.tcgdigital.vmcontrol.controller.McpController;
import com.tcgdigital.vmcontrol.dto.CreateApiTokenDTO;
import com.tcgdigital.vmcontrol.service.ApiTokenService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The MCP endpoint (E36-T01): tokens with assistant:read only, initialize and tools/list, the switch. */
class McpEndpointSecurityTest extends SecuredWebTestBase {

    @Autowired private ApiTokenService tokenService;
    @Autowired private McpController mcpController;

    private void switches(boolean tokens, boolean mcp) {
        Object tokenTarget = AopTestUtils.getTargetObject(tokenService);
        ReflectionTestUtils.setField(tokenTarget, "enabled", tokens);
        Object mcpTarget = AopTestUtils.getTargetObject(mcpController);
        ReflectionTestUtils.setField(mcpTarget, "enabled", mcp);
    }

    @BeforeEach
    void on() {
        switches(true, true);
    }

    @AfterEach
    void off() {
        switches(false, false);
    }

    private String token(String... scopes) {
        return tokenService.create(operator, new CreateApiTokenDTO("assistant", List.of(scopes), 1)).token();
    }

    private static MockHttpServletRequestBuilder rpc(String body) {
        return post("/mcp").contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(body);
    }

    @Test
    void aClientInitialisesListsToolsAndCallsWhoami() throws Exception {
        String token = token("assistant:read");

        mockMvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"))
                .andExpect(jsonPath("$.result.serverInfo.name").value("vmcontrol"))
                .andExpect(jsonPath("$.result.capabilities.tools").exists());
        mockMvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted());
        mockMvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools[?(@.name == 'whoami')]").exists())
                .andExpect(jsonPath("$.result.tools[0].annotations.readOnlyHint").value(true));
        mockMvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"whoami\",\"arguments\":{}}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.result.isError").value(false))
                .andExpect(jsonPath("$.result.structuredContent.email").value(operator.getEmail()))
                .andExpect(jsonPath("$.result.structuredContent.role").value("USER"));
        mockMvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/write\"}").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.error.code").value(-32601));
        mockMvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"stop_everything\"}}")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.error.code").value(-32602));
        mockMvc.perform(get("/mcp").header("Authorization", "Bearer " + token)).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void noTokenOrASessionCookieIs401AndAWrongScopeIs403() throws Exception {
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
        mockMvc.perform(rpc(ping)).andExpect(status().isUnauthorized());
        mockMvc.perform(rpc(ping).with(asUser())).andExpect(status().isUnauthorized());
        mockMvc.perform(rpc(ping).header("Authorization", "Bearer " + token("environments:read"))).andExpect(status().isForbidden());
        // Not even the admin scope opens the assistant endpoint.
        String adminToken = tokenService.create(admin, new CreateApiTokenDTO("adm", List.of("admin"), 1)).token();
        mockMvc.perform(rpc(ping).header("Authorization", "Bearer " + adminToken)).andExpect(status().isForbidden());
    }

    @Test
    void switchedOffTheEndpointIsNotFound() throws Exception {
        String token = token("assistant:read");
        switches(true, false);

        mockMvc.perform(rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
