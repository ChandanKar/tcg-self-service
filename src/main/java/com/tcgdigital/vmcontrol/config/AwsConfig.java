package com.tcgdigital.vmcontrol.config;

import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sts.StsClient;

/**
 * AWS beans, all backed by {@link AwsClientFactory} (E28-T01). Without credentials the context
 * still starts: the credentials provider throws only when something actually calls AWS. The
 * clients are the factory's cached ones, so Spring must not close them (destroyMethod = "").
 */
@Configuration
public class AwsConfig {

    @Bean
    public AwsCredentialsProvider awsCredentialsProvider(AwsClientFactory factory) {
        if (factory.isConfigured()) {
            return factory.baseCredentials();
        }
        return () -> {
            throw new IllegalStateException("AWS credentials not configured");
        };
    }

    @Bean(destroyMethod = "")
    public StsClient stsClient(AwsClientFactory factory) {
        return factory.isConfigured() ? factory.sts(null)
                : StsClient.builder().region(software.amazon.awssdk.regions.Region.of(factory.defaultRegion()))
                        .credentialsProvider(awsCredentialsProvider(factory)).build();
    }

    /** IAM is a global service; used only by the Cost Setup pre-flight check (E08-T11). */
    @Bean(destroyMethod = "")
    public IamClient iamClient(AwsClientFactory factory) {
        return factory.isConfigured() ? factory.iam()
                : IamClient.builder().region(software.amazon.awssdk.regions.Region.AWS_GLOBAL)
                        .credentialsProvider(awsCredentialsProvider(factory)).build();
    }
}
