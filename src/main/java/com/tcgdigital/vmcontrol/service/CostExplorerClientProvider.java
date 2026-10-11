package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;


/**
 * The one Cost Explorer client the app uses (E08-T02). Cost Explorer's API lives in us-east-1
 * whatever region the account operates in, so unlike the EC2 clients this one is never
 * per-region. Built lazily on first use; tests pass a prebuilt (mock) client instead.
 */
@Component
public class CostExplorerClientProvider {

    private final AwsClientFactory awsClientFactory;
    private final CostExplorerClient fixedClient;

    @Autowired
    public CostExplorerClientProvider(AwsClientFactory awsClientFactory) {
        this.awsClientFactory = awsClientFactory;
        this.fixedClient = null;
    }

    /** A provider around a ready client, always configured (tests). */
    CostExplorerClientProvider(CostExplorerClient client) {
        this.awsClientFactory = null;
        this.fixedClient = client;
    }

    /** A provider with no AWS credentials (tests). */
    static CostExplorerClientProvider unconfigured() {
        return new CostExplorerClientProvider((CostExplorerClient) null);
    }

    /** Whether AWS credentials are set, so a client can be built. */
    public boolean isConfigured() {
        return awsClientFactory != null ? awsClientFactory.isConfigured() : fixedClient != null;
    }

    /** The factory's cached client (E28-T02); callers must not close it. */
    public CostExplorerClient client() {
        return awsClientFactory != null ? awsClientFactory.costExplorer() : fixedClient;
    }
}
