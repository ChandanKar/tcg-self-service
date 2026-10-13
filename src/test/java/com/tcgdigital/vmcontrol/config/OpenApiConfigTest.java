package com.tcgdigital.vmcontrol.config;

import com.jayway.jsonpath.JsonPath;
import com.tcgdigital.vmcontrol.dto.CreateApiTokenDTO;
import com.tcgdigital.vmcontrol.service.ApiTokenService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The API description (E34-T04): never anonymous, the bearer scheme, and each operation's required scope. */
class OpenApiConfigTest extends SecuredWebTestBase {

    @Autowired private ApiTokenService tokenService;

    @AfterEach
    void tokensOff() {
        Object target = AopTestUtils.getTargetObject(tokenService);
        ReflectionTestUtils.setField(target, "enabled", false);
    }

    @Test
    void anonymousClientsGet401AndNoSwaggerUi() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().is3xxRedirection());
    }

    @Test
    void theSpecDescribesTheBearerSchemeAndEachOperationsScope() throws Exception {
        String spec = mockMvc.perform(get("/v3/api-docs").with(asUser()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(spec, "$.components.securitySchemes.apiToken.scheme")).isEqualTo("bearer");
        assertThat((String) JsonPath.read(spec, "$.paths['/api/v1/environments'].get['x-required-scope']"))
                .isEqualTo("environments:read");
        assertThat((String) JsonPath.read(spec, "$.paths['/api/v1/environments/{environmentId}/operations'].post['x-required-scope']"))
                .isEqualTo("operations:write");
        assertThat((String) JsonPath.read(spec, "$.paths['/api/v1/me/api-tokens'].post['x-required-scope']"))
                .isEqualTo("session-only");
    }

    @Test
    void aTokenWithAnyScopeMayReadTheSpecButNotWriteIt() throws Exception {
        Object target = AopTestUtils.getTargetObject(tokenService);
        ReflectionTestUtils.setField(target, "enabled", true);
        String token = tokenService.create(operator, new CreateApiTokenDTO("docs", List.of("cost:read"), 1)).token();

        mockMvc.perform(get("/v3/api-docs").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(post("/v3/api-docs").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }
}
