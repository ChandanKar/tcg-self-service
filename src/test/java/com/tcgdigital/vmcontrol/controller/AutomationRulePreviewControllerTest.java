package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Previewing a draft rule's next runs (E19-T04): a valid draft lists its runs and is not saved;
 * an invalid one is a 400 with the same message a create would give.
 */
class AutomationRulePreviewControllerTest extends SecuredWebTestBase {

    private Environment env;

    @BeforeEach
    void setUpEnvironment() {
        env = newEnvironment("Preview");
    }

    private String draft(String windows) {
        return "{\"environmentId\":\"" + env.getEnvironmentId() + "\",\"name\":\"Draft\",\"scopeType\":\"ENVIRONMENT\","
                + "\"triggerType\":\"SCHEDULE\",\"timezone\":\"Asia/Kolkata\",\"windows\":" + windows + "}";
    }

    @Test
    void aValidDraftListsItsRunsWithoutBeingSaved() throws Exception {
        mockMvc.perform(post("/api/v1/automation-rules/preview?limit=4").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draft("[{\"daysOfWeek\":[\"MON\",\"TUE\",\"WED\",\"THU\",\"FRI\"],\"stopTime\":\"20:00\"}]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].action").value("STOP"))
                .andExpect(jsonPath("$[0].at").value(endsWith("+05:30")))
                .andExpect(jsonPath("$[0].label").value(org.hamcrest.Matchers.containsString("20:00 IST")))
                .andExpect(jsonPath("$[0].skipped").value(false));
        mockMvc.perform(get("/api/v1/automation-rules").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void anInvalidDraftGetsTheSameMessageAsACreate() throws Exception {
        String overlapping = draft("[{\"daysOfWeek\":[\"MON\"],\"startTime\":\"08:00\",\"stopTime\":\"20:00\"},"
                + "{\"daysOfWeek\":[\"MON\"],\"startTime\":\"19:00\",\"stopTime\":\"21:00\"}]");
        expectError(mockMvc.perform(post("/api/v1/automation-rules/preview").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(overlapping)), 400)
                .andExpect(jsonPath("$.message").value("Windows overlap on MON"));
        expectError(mockMvc.perform(post("/api/v1/automation-rules").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(overlapping)), 400)
                .andExpect(jsonPath("$.message").value("Windows overlap on MON"));

        expectError(mockMvc.perform(post("/api/v1/automation-rules/preview").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(draft("[{\"daysOfWeek\":[\"MON\"],\"startTime\":\"08:00\",\"stopTime\":\"8pm\"}]"))), 400)
                .andExpect(jsonPath("$.message").value("Stop time must be in HH:mm format"));
    }

    @Test
    void onlyAdminsPreview() throws Exception {
        mockMvc.perform(post("/api/v1/automation-rules/preview").with(asUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content(draft("[{\"daysOfWeek\":[\"MON\"],\"stopTime\":\"20:00\"}]"))).andExpect(status().isForbidden());
    }
}
