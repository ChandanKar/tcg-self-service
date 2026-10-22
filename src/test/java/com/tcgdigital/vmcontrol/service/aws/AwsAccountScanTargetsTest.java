package com.tcgdigital.vmcontrol.service.aws;

import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.repository.AwsAccountRepository;
import com.tcgdigital.vmcontrol.service.CloudTarget;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which accounts and regions discovery scans (E28-T06, U19-T01). */
class AwsAccountScanTargetsTest {

    private AwsAccount account(String ref, String regions, boolean isDefault) {
        AwsAccount a = new AwsAccount();
        a.setAwsAccountRef(ref);
        a.setDisplayName(ref);
        a.setRegions(regions);
        a.setDefault(isDefault);
        return a;
    }

    private AwsAccountService service(boolean multiAccount) {
        AwsAccountRepository repository = mock(AwsAccountRepository.class);
        AwsAccount def = account(AwsAccount.DEFAULT_REF, null, true);
        when(repository.findByIsDefaultTrue()).thenReturn(Optional.of(def));
        when(repository.findByIsActiveTrueOrderByDisplayNameAsc()).thenReturn(List.of(def, account("acct-b", "eu-west-1, us-east-1", false)));
        AwsAccountService service = new AwsAccountService(repository, mock(AwsClientFactory.class));
        ReflectionTestUtils.setField(service, "multiAccountEnabled", multiAccount);
        return service;
    }

    @Test
    void everyActiveAccountWithItsOwnRegionsElseTheFallback() {
        assertThat(service(true).scanTargets(List.of("ap-south-1"))).containsExactly(
                CloudTarget.of("ap-south-1"), CloudTarget.of("acct-b", "eu-west-1"), CloudTarget.of("acct-b", "us-east-1"));
    }

    @Test
    void withMultiAccountOffOnlyTheDefaultAccountIsScanned() {
        assertThat(service(false).scanTargets(List.of("ap-south-1", "us-east-1")))
                .containsExactly(CloudTarget.of("ap-south-1"), CloudTarget.of("us-east-1"));
    }

    @Test
    void everyEnabledRegionOfEveryAccountWhenTheyCanBeRead() {
        AwsAccountService service = service(true);
        AwsRegionService regions = org.mockito.Mockito.mock(AwsRegionService.class);
        when(regions.enabledRegions(AwsAccount.DEFAULT_REF)).thenReturn(Optional.of(List.of("ap-south-1", "eu-west-1", "us-east-1")));
        when(regions.enabledRegions("acct-b")).thenReturn(Optional.of(List.of("eu-west-1", "me-central-1", "us-east-1")));
        when(regions.refusedRegions(org.mockito.ArgumentMatchers.anyString())).thenReturn(Set.of());
        service.setRegionService(regions);

        // The default account has no list of its own: every enabled region; acct-b is limited to its two.
        assertThat(service.scanTargets(List.of())).containsExactly(
                CloudTarget.of("ap-south-1"), CloudTarget.of("eu-west-1"), CloudTarget.of("us-east-1"),
                CloudTarget.of("acct-b", "eu-west-1"), CloudTarget.of("acct-b", "us-east-1"));
    }

    @Test
    void anExcludedOrRefusedRegionIsNeverScanned() {
        AwsAccountService service = service(false);
        AwsRegionService regions = org.mockito.Mockito.mock(AwsRegionService.class);
        when(regions.enabledRegions(AwsAccount.DEFAULT_REF)).thenReturn(Optional.of(List.of("ap-east-1", "ap-south-1", "eu-west-1", "us-east-1")));
        when(regions.refusedRegions(AwsAccount.DEFAULT_REF)).thenReturn(Set.of("eu-west-1"));
        service.setRegionService(regions);
        ReflectionTestUtils.setField(service, "excludeRegions", " AP-EAST-1 ,");

        assertThat(service.scanTargets(List.of())).containsExactly(CloudTarget.of("ap-south-1"), CloudTarget.of("us-east-1"));
    }

    @Test
    void regionsFollowTheEnabledListNarrowedByTheLimits() {
        Optional<List<String>> enabled = Optional.of(List.of("ap-south-1", "eu-west-1", "us-east-1"));
        assertThat(AwsAccountService.regionsFor(enabled, List.of(), List.of(), Set.of(), Set.of(), "ap-south-1"))
                .containsExactly("ap-south-1", "eu-west-1", "us-east-1");
        assertThat(AwsAccountService.regionsFor(enabled, List.of("eu-west-1", "us-east-1"), List.of("us-east-1"), Set.of(), Set.of(), "ap-south-1"))
                .containsExactly("us-east-1");
        // A limit naming a region the account has not enabled cannot be scanned there.
        assertThat(AwsAccountService.regionsFor(enabled, List.of("me-south-1"), List.of(), Set.of(), Set.of(), "ap-south-1")).isEmpty();
    }

    @Test
    void whenTheEnabledRegionsAreUnknownTheConfiguredOnesAreUsed() {
        Optional<List<String>> unknown = Optional.empty();
        assertThat(AwsAccountService.regionsFor(unknown, List.of("eu-west-1"), List.of("us-east-1"), Set.of(), Set.of(), "ap-south-1"))
                .containsExactly("eu-west-1");
        assertThat(AwsAccountService.regionsFor(unknown, List.of(), List.of("us-east-1"), Set.of(), Set.of(), "ap-south-1"))
                .containsExactly("us-east-1");
        assertThat(AwsAccountService.regionsFor(unknown, List.of(), List.of(), Set.of(), Set.of(), "ap-south-1"))
                .containsExactly("ap-south-1");
        assertThat(AwsAccountService.regionsFor(unknown, List.of(), List.of(), Set.of("ap-south-1"), Set.of(), "ap-south-1")).isEmpty();
        assertThat(AwsAccountService.regionsFor(unknown, List.of(), List.of(), Set.of(), Set.of(), null)).isEmpty();
    }

    @Test
    void aRefusedCallIsRecordedOtherFailuresAreNot() {
        AwsAccountService service = service(true);
        AwsRegionService regions = org.mockito.Mockito.mock(AwsRegionService.class);
        service.setRegionService(regions);
        software.amazon.awssdk.awscore.exception.AwsServiceException denied = software.amazon.awssdk.awscore.exception.AwsServiceException.builder()
                .message("denied").awsErrorDetails(software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder()
                        .errorCode("UnauthorizedOperation").build()).build();

        service.reportScanFailure(CloudTarget.of("acct-b", "eu-west-1"), new RuntimeException("EC2 discovery failed", denied));
        service.reportScanFailure(CloudTarget.of("acct-b", "us-east-1"), new RuntimeException("timeout"));

        org.mockito.Mockito.verify(regions).recordRefusal(org.mockito.ArgumentMatchers.eq("acct-b"), org.mockito.ArgumentMatchers.eq("eu-west-1"),
                org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.verifyNoMoreInteractions(regions);
    }
}
