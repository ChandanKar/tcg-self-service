package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Holiday calendars (E19-T02): admins change them, admins and env admins read them, others neither. */
class HolidayCalendarControllerSecurityTest extends SecuredWebTestBase {

    private String createCalendar(String name) throws Exception {
        String body = mockMvc.perform(post("/api/v1/holiday-calendars").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.calendarId");
    }

    @Test
    void onlyAdminsChangeCalendars() throws Exception {
        String json = "{\"name\":\"India 2026\"}";
        mockMvc.perform(post("/api/v1/holiday-calendars").with(asEnvAdmin())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/holiday-calendars").with(asUser())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isForbidden());

        String id = createCalendar("India 2026");
        mockMvc.perform(post("/api/v1/holiday-calendars/" + id + "/dates").with(asEnvAdmin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-10-02\"}")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/holiday-calendars/" + id).with(asEnvAdmin())).andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/v1/holiday-calendars/" + id + "/import")
                .file(new MockMultipartFile("file", "h.csv", "text/csv", "2026-10-02,x".getBytes(StandardCharsets.UTF_8)))
                .with(asEnvAdmin())).andExpect(status().isForbidden());
    }

    @Test
    void adminsAndEnvAdminsReadCalendars() throws Exception {
        String id = createCalendar("Readable");
        mockMvc.perform(post("/api/v1/holiday-calendars/" + id + "/dates").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-10-02\",\"label\":\"Gandhi Jayanti\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Gandhi Jayanti"));

        mockMvc.perform(get("/api/v1/holiday-calendars").with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Readable"))
                .andExpect(jsonPath("$[0].dateCount").value(1));
        mockMvc.perform(get("/api/v1/holiday-calendars/" + id + "/dates?size=10").with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].date").value("2026-10-02"))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mockMvc.perform(get("/api/v1/holiday-calendars").with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/holiday-calendars").with(asViewer())).andExpect(status().isForbidden());
    }

    @Test
    void anImportReportsItsInvalidLinesAndBadInputIs400() throws Exception {
        String id = createCalendar("Imported");
        String csv = "2026-10-02,Gandhi Jayanti\n2026-10-20,Dussehra\n2026-13-40,Bad\n2026-11-08,Diwali\n";
        mockMvc.perform(multipart("/api/v1/holiday-calendars/" + id + "/import")
                        .file(new MockMultipartFile("file", "h.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)))
                        .with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(3))
                .andExpect(jsonPath("$.invalid[0].line").value(3));

        expectError(mockMvc.perform(post("/api/v1/holiday-calendars").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}")), 400);
        expectError(mockMvc.perform(get("/api/v1/holiday-calendars/" + id + "/dates?page=-1").with(asAdmin())), 400);
        mockMvc.perform(get("/api/v1/holiday-calendars/nope").with(asAdmin())).andExpect(status().isNotFound());
    }
}
