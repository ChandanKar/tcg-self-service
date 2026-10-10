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

    // ---- Pagination and per-region cache (E08-T04) ----

    private static software.amazon.awssdk.services.computeoptimizer.model.InstanceRecommendation rec(String id) {
        return InstanceRecommendation.builder()
                .instanceArn("arn:aws:ec2:us-east-1:1:instance/" + id)
                .finding(Finding.OVERPROVISIONED)
                .recommendationOptions(InstanceRecommendationOption.builder().instanceType("t3.small").rank(1).build())
                .build();
    }

    private static final class MutableClock extends java.time.Clock {
        java.time.Instant now = java.time.Instant.parse("2026-10-07T10:00:00Z");
        @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
        @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public java.time.Instant instant() { return now; }
    }

    private ComputeOptimizerService enabled(software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient client,
                                            MutableClock clock) {
        ComputeOptimizerService s = new ComputeOptimizerService(region -> client, clock);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "enabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(s, "accessKey", "k");
        org.springframework.test.util.ReflectionTestUtils.setField(s, "secretKey", "s");
        return s;
    }

    @Test
    void pagesAreMergedAndASecondCallWithinTheTtlIsServedFromCache() {
        var client = org.mockito.Mockito.mock(software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient.class);
        org.mockito.Mockito.when(client.getEC2InstanceRecommendations(org.mockito.ArgumentMatchers.any(
                        software.amazon.awssdk.services.computeoptimizer.model.GetEc2InstanceRecommendationsRequest.class)))
                .thenReturn(GetEc2InstanceRecommendationsResponse.builder().instanceRecommendations(rec("i-1")).nextToken("t2").build())
                .thenReturn(GetEc2InstanceRecommendationsResponse.builder().instanceRecommendations(rec("i-2")).build());
        MutableClock clock = new MutableClock();
        ComputeOptimizerService s = enabled(client, clock);

        assertEquals(2, s.getEc2Recommendations("us-east-1").size());
        clock.now = clock.now.plusSeconds(30 * 60);
        assertEquals(2, s.getEc2Recommendations("us-east-1").size());

        var requests = org.mockito.ArgumentCaptor.forClass(
                software.amazon.awssdk.services.computeoptimizer.model.GetEc2InstanceRecommendationsRequest.class);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(2)).getEC2InstanceRecommendations(requests.capture());
        assertEquals("t2", requests.getAllValues().get(1).nextToken());
    }

    @Test
    void aFailureIsNotCached() {
        var client = org.mockito.Mockito.mock(software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient.class);
        org.mockito.Mockito.when(client.getEC2InstanceRecommendations(org.mockito.ArgumentMatchers.any(
                        software.amazon.awssdk.services.computeoptimizer.model.GetEc2InstanceRecommendationsRequest.class)))
                .thenThrow(software.amazon.awssdk.services.computeoptimizer.model.ComputeOptimizerException.builder().message("throttled").build())
                .thenReturn(GetEc2InstanceRecommendationsResponse.builder().instanceRecommendations(rec("i-1")).build());
        ComputeOptimizerService s = enabled(client, new MutableClock());

        assertTrue(s.getEc2Recommendations("us-east-1").isEmpty());
        assertEquals(1, s.getEc2Recommendations("us-east-1").size());
    }
}
