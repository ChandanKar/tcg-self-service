package com.tcgdigital.vmcontrol.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;

import java.time.Duration;

/**
 * The one Cost Explorer client the app uses (E08-T02). Cost Explorer's API lives in us-east-1
 * whatever region the account operates in, so unlike the EC2 clients this one is never
 * per-region. Built lazily on first use; tests pass a prebuilt (mock) client instead.
 */
@Component
public class CostExplorerClientProvider {

    private final String accessKey;
    private final String secretKey;
    private volatile CostExplorerClient client;

    @Autowired
    public CostExplorerClientProvider(@Value("${aws.access-key:}") String accessKey,
                                      @Value("${aws.secret-key:}") String secretKey) {
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    /** A provider around a ready client, always configured (tests). */
    CostExplorerClientProvider(CostExplorerClient client) {
        this.accessKey = "test";
        this.secretKey = "test";
        this.client = client;
    }

    /** Whether AWS credentials are set, so a client can be built. */
    public boolean isConfigured() {
        return accessKey != null && !accessKey.isEmpty() && secretKey != null && !secretKey.isEmpty();
    }

    public CostExplorerClient client() {
        CostExplorerClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client == null) {
                client = CostExplorerClient.builder()
                        .region(Region.US_EAST_1)
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)))
                        .overrideConfiguration(ClientOverrideConfiguration.builder()
                                .apiCallTimeout(Duration.ofSeconds(30))
                                .apiCallAttemptTimeout(Duration.ofSeconds(25))
                                .build())
                        .build();
            }
            return client;
        }
    }
}
