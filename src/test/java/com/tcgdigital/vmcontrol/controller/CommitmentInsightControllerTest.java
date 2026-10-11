package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.CommitmentRecommendation;
import com.tcgdigital.vmcontrol.repository.CommitmentRecommendationRepository;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The commitment insights endpoint (E27-T02): admins only, latest recommendations with their details. */
class CommitmentInsightControllerTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/cost-management/commitments/insights";

    @Autowired private CommitmentRecommendationRepository recommendations;

    @AfterEach
    void clear() {
        recommendations.deleteAll();
    }

    private void recommendation(String day, CommitmentRecommendation.Type type, String hourly) {
        CommitmentRecommendation r = new CommitmentRecommendation();
        r.setRecId(UUID.randomUUID().toString());
        r.setGeneratedOn(Date.valueOf(day));
        r.setRecType(type);
        r.setTerm("ONE_YEAR");
        r.setPaymentOption("NO_UPFRONT");
        r.setLookbackDays(30);
        r.setHourlyCommitment(hourly == null ? null : new BigDecimal(hourly));
        r.setEstimatedMonthlySavings(new BigDecimal("300"));
        r.setDetails("[{\"instanceFamily\":\"m5\",\"region\":\"ap-south-1\"}]");
        recommendations.saveAndFlush(r);
    }

    @Test
    void anAdminSeesTheLatestCapturesRecommendations() throws Exception {
        recommendation("2026-10-05", CommitmentRecommendation.Type.COMPUTE_SP, "9.99");
        recommendation("2026-10-12", CommitmentRecommendation.Type.COMPUTE_SP, "1.20");
        recommendation("2026-10-12", CommitmentRecommendation.Type.EC2_RI, null);

        mockMvc.perform(get(URL).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowDays").value(30))
                .andExpect(jsonPath("$.recommendations.length()").value(2))
                .andExpect(jsonPath("$.recommendations[0].type").value("COMPUTE_SP"))
                .andExpect(jsonPath("$.recommendations[0].hourlyCommitment").value(1.2))
                .andExpect(jsonPath("$.recommendations[0].generatedOn").value("2026-10-12"))
                .andExpect(jsonPath("$.recommendations[0].details[0].instanceFamily").value("m5"))
                .andExpect(jsonPath("$.recommendations[1].type").value("EC2_RI"));
    }

    @Test
    void onlyAdminsMayRead() throws Exception {
        mockMvc.perform(get(URL).with(asEnvAdmin())).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).with(asViewer())).andExpect(status().isForbidden());
    }
}
