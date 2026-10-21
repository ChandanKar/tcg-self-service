package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U11-T02: before sending the stop notice the dialog says how many people it reaches; the count is
 * the number the notice then emails.
 */
class EnvironmentControllerNotifyStopTest extends SecuredWebTestBase {

    private final ObjectMapper json = new ObjectMapper();
    private Environment env;

    @BeforeEach
    void seed() {
        env = newEnvironment("Payments Dev");
    }

    private int audience() throws Exception {
        String body = mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/notify-stop/audience")
                        .with(asEnvAdmin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("count").asInt();
    }

    @Test
    void theAudienceIsWhoTheNoticeEmails() throws Exception {
        int before = audience();
        for (int i = 1; i <= 3; i++) {
            User member = newUser("member-" + i + "-" + env.getEnvironmentId().substring(0, 8) + "@example.com", false, false);
            grantEnv(member, env.getEnvironmentId(), AccessLevel.USER);
        }
        // A group-only grant is not an environment member, and an inactive person is not emailed.
        User groupOnly = newUser("group-" + env.getEnvironmentId().substring(0, 8) + "@example.com", false, false);
        grantGroup(groupOnly, newGroup(env, "web").getGroupId(), AccessLevel.USER);
        User gone = newUser("gone-" + env.getEnvironmentId().substring(0, 8) + "@example.com", false, false);
        grantEnv(gone, env.getEnvironmentId(), AccessLevel.USER);
        gone.setIsActive(false);
        userRepository.saveAndFlush(gone);

        int count = audience();
        assertThat(count).isEqualTo(before + 3);

        String sent = mockMvc.perform(post("/api/v1/environments/" + env.getEnvironmentId() + "/notify-stop")
                        .with(asEnvAdmin()).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Patching\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(sent).get("emailedCount").asInt()).isEqualTo(count);
    }

    @Test
    void operatorsAndViewersCannotAsk() throws Exception {
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/notify-stop/audience").with(asUser()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/notify-stop/audience").with(asViewer()))
                .andExpect(status().isForbidden());
    }
}
