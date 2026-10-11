package com.tcgdigital.vmcontrol.service.aws;

import com.tcgdigital.vmcontrol.exception.AwsAccountUnavailableException;
import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.repository.AwsAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Credentials for one AWS account (E28-T04). The default account, an account without a role,
 * or any account while aws.multi-account.enabled is off uses the base credentials; a member
 * account assumes its role (with its external ID) from the base identity, the session being
 * refreshed by the SDK before it expires. One provider per account, until evicted.
 */
@Component
public class AwsAccountCredentialsResolver {

    private final AwsAccountRepository repository;
    private final AwsClientFactory clientFactory;
    private final Map<String, StsAssumeRoleCredentialsProvider> providers = new ConcurrentHashMap<>();

    @Value("${aws.multi-account.enabled:false}")
    private boolean multiAccountEnabled;

    @Value("${aws.assume-role.session-duration-seconds:3600}")
    private int sessionDurationSeconds = 3600;

    @Value("${aws.assume-role.session-name-prefix:vmcontrol}")
    private String sessionNamePrefix = "vmcontrol";

    public AwsAccountCredentialsResolver(AwsAccountRepository repository, AwsClientFactory clientFactory) {
        this.repository = repository;
        this.clientFactory = clientFactory;
    }

    public boolean isMultiAccountEnabled() {
        return multiAccountEnabled;
    }

    public AwsCredentialsProvider forAccount(String accountRef) {
        if (accountRef == null || AwsAccount.DEFAULT_REF.equals(accountRef) || !multiAccountEnabled) {
            return clientFactory.baseCredentials();
        }
        AwsAccount account = repository.findById(accountRef).orElseThrow(() -> new AwsAccountUnavailableException(accountRef));
        if (!account.isActive()) {
            throw new AwsAccountUnavailableException(accountRef);
        }
        if (account.isDefault() || account.getRoleArn() == null || account.getRoleArn().isBlank()) {
            return clientFactory.baseCredentials();
        }
        return providers.computeIfAbsent(accountRef, ref -> assumeRole(account));
    }

    private StsAssumeRoleCredentialsProvider assumeRole(AwsAccount account) {
        AssumeRoleRequest.Builder request = AssumeRoleRequest.builder()
                .roleArn(account.getRoleArn())
                .roleSessionName(sessionNamePrefix + "-" + account.getAwsAccountRef().substring(0, Math.min(8, account.getAwsAccountRef().length())))
                .durationSeconds(sessionDurationSeconds);
        if (account.getExternalId() != null && !account.getExternalId().isBlank()) {
            request.externalId(account.getExternalId());
        }
        return StsAssumeRoleCredentialsProvider.builder()
                .stsClient(clientFactory.sts(null))
                .refreshRequest(request.build())
                .build();
    }

    /** Drops and closes the account's provider, so the next request assumes the role afresh. */
    public void evict(String accountRef) {
        StsAssumeRoleCredentialsProvider provider = providers.remove(accountRef);
        if (provider != null) {
            provider.close();
        }
    }
}
