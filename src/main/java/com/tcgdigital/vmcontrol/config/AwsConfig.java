package com.tcgdigital.vmcontrol.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.sts.StsClient;

@Configuration
public class AwsConfig {

    @Value("${aws.access-key}")
    private String accessKey;

    @Value("${aws.secret-key}")
    private String secretKey;

    @Value("${aws.region}")
    private String region;

    @Bean
    public AwsCredentialsProvider awsCredentialsProvider() {
        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(accessKey, secretKey)
        );
    }

    @Bean
    public StsClient stsClient(AwsCredentialsProvider awsCredentialsProvider) {
        return StsClient.builder()
            .region(Region.of(region))
            .credentialsProvider(awsCredentialsProvider)
            .build();
    }

    @Bean
    public Ec2Client ec2Client(AwsCredentialsProvider awsCredentialsProvider) {
        return Ec2Client.builder()
            .region(Region.of(region))
            .credentialsProvider(awsCredentialsProvider)
            .build();
    }
}

