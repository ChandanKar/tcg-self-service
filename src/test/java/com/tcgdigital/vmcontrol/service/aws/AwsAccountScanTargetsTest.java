package com.tcgdigital.vmcontrol.service.aws;

import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.repository.AwsAccountRepository;
import com.tcgdigital.vmcontrol.service.CloudTarget;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which accounts and regions discovery scans (E28-T06). */
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
}
