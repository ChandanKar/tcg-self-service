package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.WasteFinding;
import com.tcgdigital.vmcontrol.model.WasteFindingStatus;
import com.tcgdigital.vmcontrol.model.WasteFindingType;
import com.tcgdigital.vmcontrol.repository.WasteFindingRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Waste API (E22-T03): admins only, filters, totals, dismiss/reopen, the off switch and export. */
class WasteControllerTest extends SecuredWebTestBase {

    @Autowired private WasteFindingRepository findings;

    private String volumeId;

    private String finding(WasteFindingType type, String resource, String region, String cost, WasteFindingStatus status) {
        WasteFinding f = new WasteFinding();
        f.setFindingId(UUID.randomUUID().toString());
        f.setFindingType(type);
        f.setRegion(region);
        f.setResourceId(resource);
        f.setEstMonthlyCost(new BigDecimal(cost));
        f.setStatus(status);
        f.setFirstSeenAt(new Timestamp(System.currentTimeMillis()));
        f.setLastSeenAt(new Timestamp(System.currentTimeMillis()));
        return findings.saveAndFlush(f).getFindingId();
    }

    @BeforeEach
    void setUpFindings() {
        volumeId = finding(WasteFindingType.UNATTACHED_VOLUME, "vol-1", "ap-south-1", "10.00", WasteFindingStatus.OPEN);
        finding(WasteFindingType.UNATTACHED_VOLUME, "vol-2", "eu-west-1", "4.00", WasteFindingStatus.OPEN);
        finding(WasteFindingType.UNASSOCIATED_IP, "eipalloc-1", "ap-south-1", "3.65", WasteFindingStatus.OPEN);
        finding(WasteFindingType.OLD_SNAPSHOT, "snap-1", "ap-south-1", "1.00", WasteFindingStatus.RESOLVED);
    }

    @Test
    void wasteIsForAdminsOnly() throws Exception {
        mockMvc.perform(get("/api/v1/waste/summary").with(asAdmin())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/waste/summary").with(asEnvAdmin())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/waste/findings").with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/waste/sweep").with(asViewer())).andExpect(status().isForbidden());
    }

    @Test
    void findingsFilterAndTotalsCountOpenWasteOnly() throws Exception {
        mockMvc.perform(get("/api/v1/waste/summary").with(asAdmin()))
                .andExpect(jsonPath("$.openCount").value(3))
                .andExpect(jsonPath("$.monthlyTotal").value(17.65))
                .andExpect(jsonPath("$.byType[0].type").value("UNATTACHED_VOLUME"))
                .andExpect(jsonPath("$.byType[0].monthly").value(14.0))
                .andExpect(jsonPath("$.sweepEnabled").value(false));
        mockMvc.perform(get("/api/v1/waste/findings?type=UNATTACHED_VOLUME&region=ap-south-1").with(asAdmin()))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].resourceId").value("vol-1"));
        mockMvc.perform(get("/api/v1/waste/findings?status=OPEN").with(asAdmin()))
                .andExpect(jsonPath("$.content[0].resourceId").value("vol-1")) // most expensive first
                .andExpect(jsonPath("$.page.totalElements").value(3));
    }

    @Test
    void aFindingIsDismissedWithAReasonAndReopened() throws Exception {
        expectError(mockMvc.perform(post("/api/v1/waste/findings/" + volumeId + "/dismiss").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}")), 400);
        mockMvc.perform(post("/api/v1/waste/findings/" + volumeId + "/dismiss").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"kept for the DR drill\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISMISSED"))
                .andExpect(jsonPath("$.dismissedReason").value("kept for the DR drill"));
        mockMvc.perform(get("/api/v1/waste/summary").with(asAdmin())).andExpect(jsonPath("$.openCount").value(2));
        mockMvc.perform(post("/api/v1/waste/findings/" + volumeId + "/reopen").with(asAdmin()))
                .andExpect(jsonPath("$.status").value("OPEN"));
        expectError(mockMvc.perform(post("/api/v1/waste/findings/" + volumeId + "/reopen").with(asAdmin())), 400);
    }

    @Test
    void aManualSweepIs503WhileSweepsAreOffAndExportWorks() throws Exception {
        mockMvc.perform(post("/api/v1/waste/sweep").with(asAdmin()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Waste sweeps are turned off: set waste.sweep.enabled"));
        mockMvc.perform(get("/api/v1/waste/findings/export?status=OPEN").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("waste-findings.xlsx")));
    }

    @Test
    void fixActionsAreRefusedWhileTurnedOffAndAdminOnly() throws Exception {
        expectError(mockMvc.perform(post("/api/v1/waste/findings/" + volumeId + "/actions").with(asAdmin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"DELETE_VOLUME\"}")), 400)
                .andExpect(jsonPath("$.message").value("Waste fix actions are turned off: set waste.actions.enabled"));
        mockMvc.perform(get("/api/v1/waste/actions/config").with(asAdmin())).andExpect(jsonPath("$.enabled").value(false));
        mockMvc.perform(get("/api/v1/waste/actions").with(asAdmin())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/waste/actions").with(asEnvAdmin())).andExpect(status().isForbidden());
    }
}
