package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.PurchaseOption;
import com.tcgdigital.vmcontrol.model.SpotPriceSnapshot;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.SpotPriceSnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryResponse;
import software.amazon.awssdk.services.ec2.model.SpotPrice;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Daily Spot price capture keeps the newest price per zone; lookups fall back to the region average (E31-T02). */
class SpotPriceServiceTest {

    private static final String REGION = "us-east-1";
    private static final Date TODAY = Date.valueOf("2026-10-12");

    private final SpotPriceSnapshotRepository repository = mock(SpotPriceSnapshotRepository.class);
    private final VmRepository vmRepository = mock(VmRepository.class);
    private final VmInventorySnapshotRepository inventory = mock(VmInventorySnapshotRepository.class);
    private final AwsClientFactory aws = mock(AwsClientFactory.class);
    private final Ec2Client ec2 = mock(Ec2Client.class);
    private SpotPriceService service;

    @BeforeEach
    void setUp() {
        service = new SpotPriceService(repository, vmRepository, inventory, aws);
        service.setClock(Clock.fixed(Instant.parse("2026-10-12T04:20:00Z"), ZoneOffset.UTC));
        ReflectionTestUtils.setField(service, "enabled", true);
        when(aws.isConfigured()).thenReturn(true);
        when(aws.ec2(AwsAccount.DEFAULT_REF, REGION)).thenReturn(ec2);
        when(repository.findByRegionAndAvailabilityZoneAndInstanceTypeAndCapturedOn(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    private static Vm spotNodeGroup() {
        Vm vm = new Vm("ng-1");
        vm.setProvider(CloudProvider.AWS_EKS);
        vm.setRegion(REGION);
        vm.setPurchaseOption(PurchaseOption.SPOT);
        vm.setMetadata("{\"desiredSize\":3,\"instanceType\":\"m6i.large\"}");
        return vm;
    }

    private static SpotPrice price(String az, String value, String at) {
        return SpotPrice.builder().availabilityZone(az).instanceType("m6i.large").spotPrice(value)
                .timestamp(Instant.parse(at)).build();
    }

    @Test
    void theNewestPricePerZoneIsStoredAcrossPages() {
        Vm onDemand = spotNodeGroup();
        onDemand.setVmId("ng-2");
        onDemand.setPurchaseOption(PurchaseOption.ON_DEMAND);
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(spotNodeGroup(), onDemand));
        when(ec2.describeSpotPriceHistory(any(DescribeSpotPriceHistoryRequest.class)))
                .thenReturn(DescribeSpotPriceHistoryResponse.builder().spotPriceHistory(
                        price("us-east-1a", "0.0400", "2026-10-12T03:30:00Z"),
                        price("us-east-1a", "0.0420", "2026-10-12T04:00:00Z")).nextToken("p2").build())
                .thenReturn(DescribeSpotPriceHistoryResponse.builder().spotPriceHistory(
                        price("us-east-1b", "0.0380", "2026-10-12T03:50:00Z")).build());

        assertThat(service.captureDaily()).isEqualTo(2);

        ArgumentCaptor<DescribeSpotPriceHistoryRequest> requests = ArgumentCaptor.forClass(DescribeSpotPriceHistoryRequest.class);
        verify(ec2, times(2)).describeSpotPriceHistory(requests.capture());
        assertThat(requests.getAllValues().get(0).instanceTypesAsStrings()).containsExactly("m6i.large");
        assertThat(requests.getAllValues().get(0).productDescriptions()).containsExactly("Linux/UNIX");
        assertThat(requests.getAllValues().get(1).nextToken()).isEqualTo("p2");
        ArgumentCaptor<SpotPriceSnapshot> saved = ArgumentCaptor.forClass(SpotPriceSnapshot.class);
        verify(repository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(SpotPriceSnapshot::getAvailabilityZone, s -> s.getPricePerHour().toPlainString())
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("us-east-1a", "0.0420"),
                        org.assertj.core.groups.Tuple.tuple("us-east-1b", "0.0380"));
        assertThat(saved.getAllValues()).allMatch(s -> TODAY.equals(s.getCapturedOn()));
    }

    @Test
    void turnedOffNothingIsCapturedOrLookedUp() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertThat(service.captureDaily()).isZero();
        assertThat(service.lookupSpotRate("m6i.large", REGION, "us-east-1a")).isEmpty();
        verify(ec2, never()).describeSpotPriceHistory(any(DescribeSpotPriceHistoryRequest.class));
    }

    private static SpotPriceSnapshot row(String az, String value) {
        SpotPriceSnapshot s = new SpotPriceSnapshot();
        s.setRegion(REGION);
        s.setAvailabilityZone(az);
        s.setInstanceType("m6i.large");
        s.setPricePerHour(new BigDecimal(value));
        s.setCapturedOn(TODAY);
        return s;
    }

    @Test
    void theVmsZoneWinsElseTheRegionAverage() {
        when(repository.findLatestCapturedOn(REGION, "m6i.large")).thenReturn(TODAY);
        when(repository.findByRegionAndInstanceTypeAndCapturedOn(REGION, "m6i.large", TODAY))
                .thenReturn(List.of(row("us-east-1a", "0.0420"), row("us-east-1b", "0.0380")));

        assertThat(service.lookupSpotRate("m6i.large", REGION, "us-east-1b")).contains(new BigDecimal("0.0380"));
        assertThat(service.lookupSpotRate("m6i.large", REGION, null).orElseThrow()).isEqualByComparingTo("0.04");
        assertThat(service.lookupSpotRate("m6i.large", REGION, "us-east-1f").orElseThrow()).isEqualByComparingTo("0.04");
    }

    @Test
    void aTypeNeverCapturedHasNoSpotRate() {
        when(repository.findLatestCapturedOn(REGION, "c7g.large")).thenReturn(null);

        assertThat(service.lookupSpotRate("c7g.large", REGION, null)).isEmpty();
    }
}
