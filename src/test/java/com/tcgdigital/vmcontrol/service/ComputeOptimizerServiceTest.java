package com.tcgdigital.vmcontrol.service;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.computeoptimizer.model.Finding;
import software.amazon.awssdk.services.computeoptimizer.model.GetEc2InstanceRecommendationsResponse;
import software.amazon.awssdk.services.computeoptimizer.model.InstanceRecommendation;
import software.amazon.awssdk.services.computeoptimizer.model.InstanceRecommendationOption;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeOptimizerServiceTest {

    private final ComputeOptimizerService service = new ComputeOptimizerService();

    @Test
    void parseRecommendations_extractsInstanceIdFromArnAndTopRankedOption() {
        GetEc2InstanceRecommendationsResponse response = GetEc2InstanceRecommendationsResponse.builder()
                .instanceRecommendations(InstanceRecommendation.builder()
                        .instanceArn("arn:aws:ec2:us-east-1:123456789012:instance/i-0abc123def456")
                        .finding(Finding.OVERPROVISIONED)
                        .recommendationOptions(
                                InstanceRecommendationOption.builder().instanceType("m6a.xlarge").rank(2).build(),
                                InstanceRecommendationOption.builder().instanceType("m6a.large").rank(1).build())
                        .build())
                .build();

        Map<String, ComputeOptimizerService.Recommendation> result = service.parseRecommendations(response);

        assertEquals(1, result.size());
        ComputeOptimizerService.Recommendation rec = result.get("i-0abc123def456");
        assertEquals("m6a.large", rec.suggestedInstanceType(), "must pick rank=1, not the first list entry");
        assertEquals(Finding.OVERPROVISIONED.toString(), rec.finding());
    }

    @Test
    void parseRecommendations_skipsRecommendationWithNoOptions() {
        GetEc2InstanceRecommendationsResponse response = GetEc2InstanceRecommendationsResponse.builder()
                .instanceRecommendations(InstanceRecommendation.builder()
                        .instanceArn("arn:aws:ec2:us-east-1:123456789012:instance/i-optimized")
                        .finding(Finding.OPTIMIZED)
                        .recommendationOptions(List.of())
                        .build())
                .build();

        Map<String, ComputeOptimizerService.Recommendation> result = service.parseRecommendations(response);

        assertTrue(result.isEmpty());
    }

    @Test
    void parseRecommendations_handlesEmptyResponse() {
        GetEc2InstanceRecommendationsResponse response =
                GetEc2InstanceRecommendationsResponse.builder().instanceRecommendations(List.of()).build();

        Map<String, ComputeOptimizerService.Recommendation> result = service.parseRecommendations(response);

        assertFalse(!result.isEmpty());
    }
}
