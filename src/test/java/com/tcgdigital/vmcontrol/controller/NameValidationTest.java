package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.service.support.NameSanitizer;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Names typed into create/update forms must not contain markup characters (C3).
 */
class NameValidationTest extends SecuredWebTestBase {

    private static final String MARKUP = "<img src=x>";

    @Test
    void createEnvironmentRejectsMarkupInDisplayName() throws Exception {
        expectRejected(post("/api/v1/environments"),
                "{\"name\":\"safe-env\",\"displayName\":\"" + MARKUP + "\",\"cloudProvider\":\"AWS\"}");
    }

    @Test
    void createEnvironmentRejectsBacktickInName() throws Exception {
        expectRejected(post("/api/v1/environments"),
                "{\"name\":\"env`x\",\"displayName\":\"Safe\",\"cloudProvider\":\"AWS\"}");
    }

    @Test
    void createEnvironmentAcceptsApostropheAndAmpersand() throws Exception {
        mockMvc.perform(post("/api/v1/environments").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"obrien-co\",\"displayName\":\"O'Brien & Co\",\"cloudProvider\":\"AWS\"}"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void updateEnvironmentRejectsMarkup() throws Exception {
        Environment env = newEnvironment("Name Rules");
        expectRejected(put("/api/v1/environments/" + env.getEnvironmentId()),
                "{\"displayName\":\"" + MARKUP + "\"}");
    }

    @Test
    void createAndUpdateGroupRejectMarkup() throws Exception {
        Environment env = newEnvironment("Name Rules");
        VmGroup group = newGroup(env, "web");
        String body = "{\"name\":\"web2\",\"displayName\":\"" + MARKUP + "\",\"sequencePosition\":2}";
        expectRejected(post("/api/v1/environments/" + env.getEnvironmentId() + "/groups"), body);
        expectRejected(put("/api/v1/environments/" + env.getEnvironmentId() + "/groups/" + group.getGroupId()), body);
    }

    @Test
    void registerVmRejectsMarkup() throws Exception {
        Environment env = newEnvironment("Name Rules");
        VmGroup group = newGroup(env, "web");
        expectRejected(post("/api/v1/environments/" + env.getEnvironmentId() + "/vms"),
                "{\"groupId\":\"" + group.getGroupId() + "\",\"name\":\"vm<1>\",\"displayName\":\"VM 1\","
                        + "\"provider\":\"AWS\",\"region\":\"us-east-1\",\"providerVmId\":\"i-0123456789abcdef0\","
                        + "\"sequencePosition\":1}");
    }

    private void expectRejected(MockHttpServletRequestBuilder request, String body) throws Exception {
        expectError(mockMvc.perform(request.with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)), 400)
                .andExpect(jsonPath("$.message", containsString(NameSanitizer.SAFE_NAME_MESSAGE)));
    }
}
