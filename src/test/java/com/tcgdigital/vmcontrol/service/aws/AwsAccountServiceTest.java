package com.tcgdigital.vmcontrol.service.aws;

import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.AwsAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Account resolution order and the default account's number from STS (E28-T03). */
class AwsAccountServiceTest {

    private AwsAccountRepository repository;
    private AwsClientFactory factory;
    private StsClient sts;
    private AwsAccountService service;
    private AwsAccount defaultAccount;

    @BeforeEach
    void setUp() {
        repository = mock(AwsAccountRepository.class);
        factory = mock(AwsClientFactory.class);
        sts = mock(StsClient.class);
        when(factory.isConfigured()).thenReturn(true);
        when(factory.sts(null)).thenReturn(sts);
        defaultAccount = new AwsAccount();
        defaultAccount.setAwsAccountRef(AwsAccount.DEFAULT_REF);
        defaultAccount.setDefault(true);
        when(repository.findByIsDefaultTrue()).thenReturn(Optional.of(defaultAccount));
        service = new AwsAccountService(repository, factory);
    }

    private static Vm vmIn(String vmRef, String envRef) {
        Environment env = new Environment();
        env.setAwsAccountRef(envRef);
        VmGroup group = new VmGroup();
        group.setEnvironment(env);
        Vm vm = new Vm();
        vm.setGroup(group);
        vm.setAwsAccountRef(vmRef);
        return vm;
    }

    @Test
    void theVmsAccountWinsThenItsEnvironmentsThenTheDefault() {
        assertThat(service.resolveRef(vmIn("vm-acct", "env-acct"))).isEqualTo("vm-acct");
        assertThat(service.resolveRef(vmIn(null, "env-acct"))).isEqualTo("env-acct");
        assertThat(service.resolveRef(vmIn(null, null))).isEqualTo(AwsAccount.DEFAULT_REF);
        assertThat(service.resolveRef((Vm) null)).isEqualTo(AwsAccount.DEFAULT_REF);
        assertThat(service.resolveRef((Environment) null)).isEqualTo(AwsAccount.DEFAULT_REF);
    }

    @Test
    void theDefaultAccountNumberComesFromSts() {
        when(sts.getCallerIdentity()).thenReturn(GetCallerIdentityResponse.builder().account("123456789012").build());

        service.resolveDefaultAccountNumber();

        assertThat(defaultAccount.getAccountNumber()).isEqualTo("123456789012");
        verify(repository).save(defaultAccount);
    }

    @Test
    void aKnownNumberIsNotLookedUpAgain() {
        defaultAccount.setAccountNumber("111111111111");

        service.resolveDefaultAccountNumber();

        verifyNoInteractions(sts);
        verify(repository, never()).save(any());
    }

    @Test
    void anStsFailureIsSwallowed() {
        when(sts.getCallerIdentity()).thenThrow(SdkClientException.create("Unable to load credentials"));

        service.resolveDefaultAccountNumber();

        assertThat(defaultAccount.getAccountNumber()).isNull();
        verify(repository, never()).save(any());
    }

    @Test
    void withoutCredentialsNothingIsCalledOnStartup() {
        when(factory.isConfigured()).thenReturn(false);

        service.onReady();
        service.resolveDefaultAccountNumber();

        verifyNoInteractions(sts);
    }
}
