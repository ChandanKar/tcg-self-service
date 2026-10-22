package com.tcgdigital.vmcontrol.service.aws;

import com.tcgdigital.vmcontrol.model.AwsAccountRegion;
import com.tcgdigital.vmcontrol.repository.AwsAccountRegionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsRequest;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse;
import software.amazon.awssdk.services.ec2.model.Region;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Each account's enabled regions (U19-T01): read from DescribeRegions, opt-in regions included, kept a
 * day; a failed read keeps the last one and waits an hour; refused regions are skipped for a day.
 */
class AwsRegionServiceTest {

    private static final String REF = "acct-a";

    private final List<AwsAccountRegion> rows = new ArrayList<>();
    private AwsAccountRegionRepository repository;
    private Ec2Client ec2;
    private AwsRegionService service;
    private Instant now = Instant.parse("2026-10-22T08:00:00Z");
    private final AtomicBoolean configured = new AtomicBoolean(true);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(AwsAccountRegionRepository.class);
        when(repository.findByAwsAccountRefOrderByRegionCodeAsc(anyString())).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getAwsAccountRef().equals(inv.getArgument(0)))
                .sorted((a, b) -> a.getRegionCode().compareTo(b.getRegionCode())).toList());
        when(repository.findByAwsAccountRefAndRegionCode(anyString(), anyString())).thenAnswer(inv -> rows.stream()
                .filter(r -> r.getAwsAccountRef().equals(inv.getArgument(0)) && r.getRegionCode().equals(inv.getArgument(1))).findFirst());
        when(repository.saveAll(any())).thenAnswer(inv -> {
            ((Iterable<AwsAccountRegion>) inv.getArgument(0)).forEach(this::keep);
            return null;
        });
        when(repository.save(any())).thenAnswer(inv -> {
            keep(inv.getArgument(0));
            return inv.getArgument(0);
        });
        doAnswer(inv -> {
            ((Iterable<AwsAccountRegion>) inv.getArgument(0)).forEach(rows::remove);
            return null;
        }).when(repository).deleteAll(any());
        ec2 = mock(Ec2Client.class);
        service = new AwsRegionService(repository, (ref, region) -> ec2, () -> "ap-south-1", configured::get);
        service.setClock(clockAt(now));
    }

    private void keep(AwsAccountRegion row) {
        if (!rows.contains(row)) {
            rows.add(row);
        }
    }

    private static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    private void later(Duration by) {
        now = now.plus(by);
        service.setClock(clockAt(now));
    }

    private void awsSays(String... regions) {
        List<Region> list = new ArrayList<>();
        for (String r : regions) {
            String[] parts = r.split(":");
            list.add(Region.builder().regionName(parts[0]).optInStatus(parts.length > 1 ? parts[1] : "opt-in-not-required").build());
        }
        when(ec2.describeRegions(any(DescribeRegionsRequest.class))).thenReturn(DescribeRegionsResponse.builder().regions(list).build());
    }

    private static AwsServiceException awsError(String code) {
        return AwsServiceException.builder().message(code)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage(code).build()).build();
    }

    @Test
    void enabledRegionsAreReadFromAwsWithOptInRegionsAndKeptADay() {
        awsSays("us-east-1", "ap-south-1", "me-central-1:opted-in", "af-south-1:not-opted-in");

        assertThat(service.enabledRegions(REF)).contains(List.of("ap-south-1", "me-central-1", "us-east-1"));
        later(Duration.ofHours(23));
        assertThat(service.enabledRegions(REF)).contains(List.of("ap-south-1", "me-central-1", "us-east-1"));
        verify(ec2, times(1)).describeRegions(any(DescribeRegionsRequest.class));
    }

    @Test
    void aRegionEnabledTodayIsScannedTomorrow() {
        awsSays("us-east-1");
        service.enabledRegions(REF);
        awsSays("us-east-1", "eu-south-2:opted-in");

        later(Duration.ofHours(25));

        assertThat(service.enabledRegions(REF)).contains(List.of("eu-south-2", "us-east-1"));
    }

    @Test
    void aRegionNoLongerEnabledIsDropped() {
        awsSays("us-east-1", "me-south-1:opted-in");
        service.refresh(REF);
        awsSays("us-east-1");

        service.refresh(REF);

        assertThat(rows).extracting(AwsAccountRegion::getRegionCode).containsExactly("us-east-1");
    }

    @Test
    void aFailedReadKeepsTheLastOneAndIsNotRetriedForAnHour() {
        awsSays("us-east-1");
        service.enabledRegions(REF);
        later(Duration.ofDays(2));
        when(ec2.describeRegions(any(DescribeRegionsRequest.class))).thenThrow(SdkClientException.create("timeout"));

        assertThat(service.enabledRegions(REF)).contains(List.of("us-east-1"));
        later(Duration.ofMinutes(30));
        assertThat(service.enabledRegions(REF)).contains(List.of("us-east-1"));
        verify(ec2, times(2)).describeRegions(any(DescribeRegionsRequest.class));

        later(Duration.ofMinutes(31));
        service.enabledRegions(REF);
        verify(ec2, times(3)).describeRegions(any(DescribeRegionsRequest.class));
    }

    @Test
    void neverReadAndNoAwsAccessMeansUnknown() {
        configured.set(false);

        assertThat(service.enabledRegions(REF)).isEmpty();
        verifyNoInteractions(ec2);
    }

    @Test
    void aRefreshThatFailsThrowsAndLeavesTheStoredRegions() {
        awsSays("us-east-1");
        service.refresh(REF);
        when(ec2.describeRegions(any(DescribeRegionsRequest.class))).thenThrow(awsError("UnauthorizedOperation"));

        assertThatThrownBy(() -> service.refresh(REF)).isInstanceOf(AwsServiceException.class);
        assertThat(rows).extracting(AwsAccountRegion::getRegionCode).containsExactly("us-east-1");
    }

    @Test
    void aRefusedRegionIsSkippedForADayAndKeptAcrossARefresh() {
        awsSays("us-east-1", "eu-west-1");
        service.refresh(REF);

        service.recordRefusal(REF, "eu-west-1", "UnauthorizedOperation: denied by an SCP");
        service.refresh(REF);

        assertThat(service.refusedRegions(REF)).containsExactly("eu-west-1");
        assertThat(service.view(REF)).filteredOn(AwsRegionService.RegionView::refused)
                .extracting(AwsRegionService.RegionView::refusedReason).containsExactly("UnauthorizedOperation: denied by an SCP");
        later(Duration.ofHours(25));
        assertThat(service.refusedRegions(REF)).isEmpty();
        assertThat(service.view(REF)).noneMatch(AwsRegionService.RegionView::refused);
    }

    @Test
    void aRefusalInARegionNeverReadDoesNotCountAsARead() {
        configured.set(false);
        service.recordRefusal(REF, "eu-west-1", "AuthFailure");

        assertThat(service.enabledRegions(REF)).isEmpty();
        assertThat(service.refusedRegions(REF)).containsExactly("eu-west-1");
    }

    @Test
    void accessErrorsAreRefusalsOtherFailuresAreNot() {
        assertThat(AwsRegionService.isRefusal(awsError("UnauthorizedOperation"))).isTrue();
        assertThat(AwsRegionService.isRefusal(awsError("AuthFailure"))).isTrue();
        assertThat(AwsRegionService.isRefusal(awsError("OptInRequired"))).isTrue();
        assertThat(AwsRegionService.isRefusal(new RuntimeException("wrapped", awsError("AccessDeniedException")))).isTrue();
        assertThat(AwsRegionService.isRefusal(awsError("RequestLimitExceeded"))).isFalse();
        assertThat(AwsRegionService.isRefusal(SdkClientException.create("timeout"))).isFalse();
        assertThat(AwsRegionService.isRefusal(null)).isFalse();
    }

    @Test
    void theReadUsesTheAccountsOwnClientInTheHomeRegion() {
        java.util.List<String> asked = new ArrayList<>();
        AwsRegionService own = new AwsRegionService(repository, (ref, region) -> {
            asked.add(ref + "@" + region);
            return ec2;
        }, () -> "ap-south-1", () -> true);
        awsSays("us-east-1");

        own.refresh(REF);

        assertThat(asked).containsExactly("acct-a@ap-south-1");
        verify(ec2).describeRegions(eq(DescribeRegionsRequest.builder().allRegions(false).build()));
    }

    @Test
    void storedRowsKeepTheirReadTime() {
        awsSays("us-east-1");
        service.refresh(REF);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.getReadAt()).isEqualTo(Timestamp.from(now));
            assertThat(r.getOptInStatus()).isEqualTo("opt-in-not-required");
        });
        assertThat(Optional.ofNullable(rows.get(0).getRefusedAt())).isEmpty();
    }
}
