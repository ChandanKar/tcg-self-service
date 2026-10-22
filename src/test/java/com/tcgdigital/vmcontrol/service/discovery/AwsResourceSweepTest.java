package com.tcgdigital.vmcontrol.service.discovery;

import com.tcgdigital.vmcontrol.service.CloudTarget;
import com.tcgdigital.vmcontrol.service.aws.AwsAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The discovery engine (U19-T02) over a simulated AWS: many regions, throttling that slows the sweep
 * instead of failing it, refused and broken regions that never stop the others and are never read as
 * "nothing there", and Resource Explorer narrowing the regions.
 */
class AwsResourceSweepTest {

    private static final List<String> REGIONS = List.of("ap-south-1", "ap-southeast-1", "ap-southeast-2", "ap-northeast-1",
            "ap-northeast-2", "ap-northeast-3", "ca-central-1", "eu-central-1", "eu-west-1", "eu-west-2", "eu-west-3",
            "eu-north-1", "sa-east-1", "us-east-1", "us-east-2", "us-west-1", "us-west-2", "me-central-1", "af-south-1", "il-central-1");

    private AwsAccountService accounts;
    private AwsResourceSweep sweep;
    private final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        accounts = mock(AwsAccountService.class);
        sweep = new AwsResourceSweep(accounts);
        sweep.setLimits(8, 5, 100);
        sweep.setSleeper(sleeps::add);
    }

    private static List<CloudTarget> targets(String account, List<String> regions) {
        return regions.stream().map(r -> CloudTarget.of(account, r)).toList();
    }

    private static AwsServiceException aws(String code) {
        return AwsServiceException.builder().message(code)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(code).build()).build();
    }

    @Test
    void everyRegionOfEveryAccountIsScannedAFewAtATime() {
        List<CloudTarget> all = new ArrayList<>(targets("default", REGIONS));
        all.addAll(targets("acct-b", REGIONS.subList(0, 5)));
        when(accounts.scanTargets(List.of())).thenReturn(all);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();

        AwsResourceSweep.Result<String> result = sweep.everywhere("EC2 instances", List.of(), null, t -> {
            peak.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            // Two "pages" of results per region, joined by the provider's paginator.
            return List.of(t.region() + "/i-1", t.region() + "/i-2");
        });

        assertThat(result.found()).hasSize(25);
        assertThat(result.all()).hasSize(50);
        assertThat(result.failures()).isEmpty();
        assertThat(peak.get()).isBetween(2, 8);
        assertThat(sweep.lastOutcomes()).hasSize(25).allMatch(AwsResourceSweep.RegionOutcome::ok);
    }

    @Test
    void throttlingSlowsTheSweepDownInsteadOfFailingIt() {
        Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        AwsResourceSweep.Result<String> result = sweep.run("EKS clusters", targets("default", REGIONS.subList(0, 4)), t -> {
            int n = calls.computeIfAbsent(t.region(), k -> new AtomicInteger()).incrementAndGet();
            if (t.region().equals("ap-south-1") && n <= 3) {
                throw new RuntimeException("wrapped", aws("RequestLimitExceeded"));
            }
            return List.of(t.region() + "/cluster");
        });

        assertThat(result.failures()).isEmpty();
        assertThat(result.found()).hasSize(4);
        assertThat(calls.get("ap-south-1")).hasValue(4);
        // Three waits, each about twice the one before (with up to half again as jitter).
        assertThat(sleeps).hasSize(3);
        assertThat(sleeps.get(0)).isBetween(100L, 150L);
        assertThat(sleeps.get(1)).isBetween(200L, 300L);
        assertThat(sleeps.get(2)).isBetween(400L, 600L);
    }

    @Test
    void endlessThrottlingGivesUpOnThatRegionOnly() {
        AwsResourceSweep.Result<String> result = sweep.run("EC2 instances", targets("default", List.of("eu-west-1", "us-east-1")), t -> {
            if (t.region().equals("eu-west-1")) throw aws("Throttling");
            return List.of("i-us");
        });

        assertThat(result.failures()).singleElement().satisfies(o -> {
            assertThat(o.target()).isEqualTo(CloudTarget.of("default", "eu-west-1"));
            assertThat(o.refused()).isFalse();
        });
        assertThat(result.answered(CloudTarget.of("default", "us-east-1"))).isTrue();
        assertThat(sleeps).hasSize(4); // five attempts
    }

    @Test
    void aRefusedOrBrokenRegionNeverStopsTheOthersAndIsNeverReadAsEmpty() {
        CloudTarget refused = CloudTarget.of("default", "me-central-1");
        CloudTarget broken = CloudTarget.of("acct-b", "eu-west-1");
        List<CloudTarget> all = new ArrayList<>(targets("default", REGIONS.subList(0, 6)));
        all.add(refused);
        all.add(broken);
        AwsServiceException denied = aws("UnauthorizedOperation");

        AwsResourceSweep.Result<String> result = sweep.run("EC2 instances", all, t -> {
            if (t.equals(refused)) throw new RuntimeException("EC2 discovery failed in " + t, denied);
            if (t.equals(broken)) throw new IllegalStateException("connection reset");
            return List.of(t.region());
        });

        assertThat(result.found()).hasSize(6).doesNotContainKeys(refused, broken);
        assertThat(result.answered(refused)).isFalse();
        assertThat(result.failures()).extracting(AwsResourceSweep.RegionOutcome::refused).containsExactlyInAnyOrder(true, false);
        verify(accounts).reportScanFailure(eq(refused), any());
        verify(accounts).reportScanFailure(eq(broken), any());
    }

    @Test
    void aRegionThatTakesTooLongIsReportedAsTimedOut() {
        org.springframework.test.util.ReflectionTestUtils.setField(sweep, "timeoutSeconds", 1L);
        AwsResourceSweep.Result<String> result = sweep.run("EC2 instances", targets("default", List.of("eu-west-1", "us-east-1")), t -> {
            if (t.region().equals("eu-west-1")) {
                try {
                    Thread.sleep(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return List.of(t.region());
        });

        assertThat(result.failures()).singleElement().extracting(AwsResourceSweep.RegionOutcome::error).isEqualTo("Timed out");
        assertThat(result.answered(CloudTarget.of("default", "us-east-1"))).isTrue();
    }

    @Test
    void resourceExplorerNarrowsTheRegionsWhenItCan() {
        List<CloudTarget> all = targets("default", REGIONS);
        when(accounts.scanTargets(List.of())).thenReturn(all);
        ResourceExplorerSearch explorer = mock(ResourceExplorerSearch.class);
        when(explorer.narrow(all, "eks:cluster")).thenReturn(targets("default", List.of("eu-west-1", "us-east-2")));
        sweep.setExplorer(explorer);
        List<String> asked = Collections.synchronizedList(new ArrayList<>());

        sweep.everywhere("EKS clusters", List.of(), "eks:cluster", t -> {
            asked.add(t.region());
            return List.of();
        });

        assertThat(asked).containsExactlyInAnyOrder("eu-west-1", "us-east-2");
    }

    @Test
    void throttlingIsRecognisedThroughWrappingExceptions() {
        assertThat(AwsResourceSweep.isThrottling(new RuntimeException("x", aws("ThrottlingException")))).isTrue();
        assertThat(AwsResourceSweep.isThrottling(aws("TooManyRequestsException"))).isTrue();
        assertThat(AwsResourceSweep.isThrottling(aws("UnauthorizedOperation"))).isFalse();
        assertThat(AwsResourceSweep.isThrottling(new IllegalStateException("no"))).isFalse();
    }

    @Test
    void noTargetsMeansNoCalls() {
        AtomicInteger calls = new AtomicInteger();
        assertThat(sweep.run("EC2 instances", List.of(), t -> {
            calls.incrementAndGet();
            return List.of();
        }).found()).isEmpty();
        assertThat(calls).hasValue(0);
    }
}
