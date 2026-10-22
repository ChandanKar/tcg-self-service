package com.tcgdigital.vmcontrol.service.discovery;

import com.tcgdigital.vmcontrol.service.CloudTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.resourceexplorer2.ResourceExplorer2Client;
import software.amazon.awssdk.services.resourceexplorer2.model.Index;
import software.amazon.awssdk.services.resourceexplorer2.model.IndexType;
import software.amazon.awssdk.services.resourceexplorer2.model.ListIndexesRequest;
import software.amazon.awssdk.services.resourceexplorer2.model.ListIndexesResponse;
import software.amazon.awssdk.services.resourceexplorer2.model.Resource;
import software.amazon.awssdk.services.resourceexplorer2.model.SearchRequest;
import software.amazon.awssdk.services.resourceexplorer2.model.SearchResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Resource Explorer as a shortcut for the sweep (U19-T02), and every way it falls back to sweeping all regions. */
class ResourceExplorerSearchTest {

    private ResourceExplorer2Client home;
    private ResourceExplorer2Client aggregator;
    private final List<String> clientRegions = new ArrayList<>();
    private ResourceExplorerSearch search;
    private Instant now = Instant.parse("2026-10-22T08:00:00Z");

    private static final List<CloudTarget> TARGETS = List.of(CloudTarget.of("eu-west-1"), CloudTarget.of("us-east-1"),
            CloudTarget.of("ap-south-1"), CloudTarget.of("acct-b", "eu-west-1"));

    @BeforeEach
    void setUp() {
        home = mock(ResourceExplorer2Client.class);
        aggregator = mock(ResourceExplorer2Client.class);
        search = new ResourceExplorerSearch((ref, region) -> {
            clientRegions.add(ref + "@" + region);
            return "us-east-1".equals(region) ? aggregator : home;
        }, () -> "ap-south-1");
        search.setClock(Clock.fixed(now, ZoneOffset.UTC));
    }

    private void aggregatorIn(String region) {
        when(home.listIndexes(any(ListIndexesRequest.class))).thenReturn(ListIndexesResponse.builder()
                .indexes(Index.builder().region(region).type(IndexType.AGGREGATOR).arn("arn:index").build()).build());
    }

    private static Resource at(String region) {
        return Resource.builder().region(region).resourceType("ec2:instance").arn("arn:aws:ec2:" + region + ":1:instance/i-1").build();
    }

    @Test
    void withAnAggregatorIndexOnlyTheRegionsHoldingTheTypeAreSwept() {
        aggregatorIn("us-east-1");
        when(aggregator.search(any(SearchRequest.class))).thenReturn(SearchResponse.builder()
                .resources(at("eu-west-1"), at("eu-west-1"), at("global")).nextToken("p2").build())
                .thenReturn(SearchResponse.builder().resources(at("us-east-1")).build());

        List<CloudTarget> narrowed = search.narrow(TARGETS, "ec2:instance");

        // acct-b has no index of its own (its ListIndexes answers the same mock: an aggregator in us-east-1).
        assertThat(narrowed).contains(CloudTarget.of("eu-west-1"), CloudTarget.of("us-east-1")).doesNotContain(CloudTarget.of("ap-south-1"));
        verify(aggregator, atLeastOnce()).search(org.mockito.ArgumentMatchers.<SearchRequest>argThat(r -> r.queryString().equals("resourcetype:ec2:instance")));
        assertThat(clientRegions).contains("00000000-0000-0000-0000-000000000001@ap-south-1",
                "00000000-0000-0000-0000-000000000001@us-east-1");
    }

    @Test
    void withoutAnAggregatorIndexEveryRegionIsSweptAndListIndexesIsNotAskedAgainForADay() {
        when(home.listIndexes(any(ListIndexesRequest.class))).thenReturn(ListIndexesResponse.builder().build());

        assertThat(search.narrow(TARGETS, "ec2:instance")).isEqualTo(TARGETS);
        assertThat(search.narrow(TARGETS, "eks:cluster")).isEqualTo(TARGETS);
        search.setClock(Clock.fixed(now.plus(Duration.ofHours(23)), ZoneOffset.UTC));
        assertThat(search.narrow(TARGETS, "ec2:instance")).isEqualTo(TARGETS);

        verify(home, times(2)).listIndexes(any(ListIndexesRequest.class)); // once per account
    }

    @Test
    void noDefaultViewOrNoPermissionFallsBackToEveryRegion() {
        aggregatorIn("us-east-1");
        when(aggregator.search(any(SearchRequest.class))).thenThrow(AwsServiceException.builder().message("no default view")
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("UnauthorizedException").build()).build());

        assertThat(search.narrow(TARGETS, "eks:cluster")).isEqualTo(TARGETS);
    }

    @Test
    void answersAreKeptFifteenMinutes() {
        aggregatorIn("us-east-1");
        when(aggregator.search(any(SearchRequest.class))).thenReturn(SearchResponse.builder().resources(at("eu-west-1")).build());

        search.narrow(TARGETS, "ec2:instance");
        search.setClock(Clock.fixed(now.plus(Duration.ofMinutes(10)), ZoneOffset.UTC));
        search.narrow(TARGETS, "ec2:instance");
        verify(aggregator, times(2)).search(any(SearchRequest.class)); // once per account
        search.setClock(Clock.fixed(now.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));
        search.narrow(TARGETS, "ec2:instance");
        verify(aggregator, times(4)).search(any(SearchRequest.class));
    }

    @Test
    void switchedOffItLeavesTheTargetsAlone() {
        search.setEnabled(false);
        assertThat(search.narrow(TARGETS, "ec2:instance")).isEqualTo(TARGETS);
        verifyNoInteractions(home, aggregator);
    }
}
