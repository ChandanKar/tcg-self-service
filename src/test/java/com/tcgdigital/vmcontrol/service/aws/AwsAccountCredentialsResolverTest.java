package com.tcgdigital.vmcontrol.service.aws;

import com.tcgdigital.vmcontrol.exception.AwsAccountUnavailableException;
import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.repository.AwsAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Per-account credentials through STS AssumeRole (E28-T04), with a mocked STS client. */
class AwsAccountCredentialsResolverTest {

    private static final String MEMBER = "11111111-2222-3333-4444-555555555555";

    private AwsAccountRepository repository;
    private StsClient sts;
    private AwsCredentialsProvider base;
    private AwsAccountCredentialsResolver resolver;
    private AwsAccount member;

    @BeforeEach
    void setUp() {
        repository = mock(AwsAccountRepository.class);
        sts = mock(StsClient.class);
        base = StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIA-base", "base-secret"));
        AwsClientFactory factory = mock(AwsClientFactory.class);
        when(factory.baseCredentials()).thenReturn(base);
        when(factory.sts(null)).thenReturn(sts);
        member = new AwsAccount();
        member.setAwsAccountRef(MEMBER);
        member.setDisplayName("Member");
        member.setRoleArn("arn:aws:iam::210987654321:role/VmControlAccess");
        member.setExternalId("ext-1234");
        when(repository.findById(MEMBER)).thenReturn(Optional.of(member));
        when(sts.assumeRole(any(AssumeRoleRequest.class))).thenReturn(AssumeRoleResponse.builder()
                .credentials(Credentials.builder().accessKeyId("ASIA-member").secretAccessKey("s").sessionToken("t")
                        .expiration(Instant.now().plus(1, ChronoUnit.HOURS)).build())
                .build());
        resolver = new AwsAccountCredentialsResolver(repository, factory);
        ReflectionTestUtils.setField(resolver, "multiAccountEnabled", true);
    }

    @Test
    void aMemberAccountAssumesItsRoleWithTheExternalId() {
        AwsCredentialsProvider provider = resolver.forAccount(MEMBER);

        assertThat(provider).isInstanceOf(StsAssumeRoleCredentialsProvider.class);
        assertThat(provider.resolveCredentials().accessKeyId()).isEqualTo("ASIA-member");
        ArgumentCaptor<AssumeRoleRequest> request = ArgumentCaptor.forClass(AssumeRoleRequest.class);
        verify(sts).assumeRole(request.capture());
        assertThat(request.getValue().roleArn()).isEqualTo("arn:aws:iam::210987654321:role/VmControlAccess");
        assertThat(request.getValue().externalId()).isEqualTo("ext-1234");
        assertThat(request.getValue().roleSessionName()).isEqualTo("vmcontrol-11111111");
        assertThat(request.getValue().durationSeconds()).isEqualTo(3600);
        assertThat(resolver.forAccount(MEMBER)).isSameAs(provider);
    }

    @Test
    void withTheFlagOffOrForTheDefaultAccountTheBaseCredentialsAreUsed() {
        assertThat(resolver.forAccount(AwsAccount.DEFAULT_REF)).isSameAs(base);
        assertThat(resolver.forAccount(null)).isSameAs(base);

        ReflectionTestUtils.setField(resolver, "multiAccountEnabled", false);
        assertThat(resolver.forAccount(MEMBER)).isSameAs(base);
        verifyNoInteractions(sts);
    }

    @Test
    void anAccountWithoutARoleUsesTheBaseCredentials() {
        member.setRoleArn(null);
        assertThat(resolver.forAccount(MEMBER)).isSameAs(base);
    }

    @Test
    void unknownOrInactiveAccountsAreRefused() {
        assertThatThrownBy(() -> resolver.forAccount("nope")).isInstanceOf(AwsAccountUnavailableException.class);
        member.setActive(false);
        assertThatThrownBy(() -> resolver.forAccount(MEMBER)).isInstanceOf(AwsAccountUnavailableException.class)
                .hasMessageContaining(MEMBER);
    }

    @Test
    void evictingBuildsANewProviderNextTime() {
        AwsCredentialsProvider first = resolver.forAccount(MEMBER);
        resolver.evict(MEMBER);
        assertThat(resolver.forAccount(MEMBER)).isNotSameAs(first);
        resolver.evict("never-cached");
    }
}
