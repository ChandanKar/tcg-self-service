package com.tcgdigital.vmcontrol;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class to validate AWS configuration.
 *
 * Note: These tests require valid AWS credentials to be configured in application.properties.
 * Tests will be skipped if placeholder credentials are detected.
 */
@SpringBootTest
class AwsConfigurationTest {

    @Autowired
    private AwsCredentialsProvider awsCredentialsProvider;

    @Autowired
    private StsClient stsClient;

    @Value("${aws.access-key}")
    private String accessKey;

    @Value("${aws.secret-key}")
    private String secretKey;

    @Value("${aws.region}")
    private String region;

    @Test
    void testAwsCredentialsProviderBeanExists() {
        assertNotNull(awsCredentialsProvider, "AwsCredentialsProvider bean should be created");
    }

    @Test
    void testStsClientBeanExists() {
        assertNotNull(stsClient, "StsClient bean should be created");
    }

    @Test
    void testAwsPropertiesLoaded() {
        assertNotNull(accessKey, "AWS access key should be loaded from properties");
        assertNotNull(secretKey, "AWS secret key should be loaded from properties");
        assertNotNull(region, "AWS region should be loaded from properties");
        assertFalse(accessKey.isEmpty(), "AWS access key should not be empty");
        assertFalse(secretKey.isEmpty(), "AWS secret key should not be empty");
        assertFalse(region.isEmpty(), "AWS region should not be empty");
    }

    /**
     * This test validates that the AWS credentials are correct by calling STS GetCallerIdentity.
     * This test will fail if the credentials are invalid or are placeholder values.
     *
     * Skip this test in CI/CD environments where real credentials may not be available.
     */
    @Test
    void testAwsCredentialsAreValid() {
        // Skip test if using placeholder credentials
        if ("YOUR_AWS_ACCESS_KEY".equals(accessKey) || "YOUR_AWS_SECRET_KEY".equals(secretKey)) {
            System.out.println("Skipping AWS credential validation test - placeholder credentials detected. " +
                "Please configure real AWS credentials in application.properties to run this test.");
            return;
        }

        try {
            // GetCallerIdentity is a free API call that validates credentials
            GetCallerIdentityResponse response = stsClient.getCallerIdentity();

            assertNotNull(response.account(), "AWS Account ID should be returned");
            assertNotNull(response.arn(), "AWS ARN should be returned");
            assertNotNull(response.userId(), "AWS User ID should be returned");

            System.out.println("AWS Credentials validated successfully!");
            System.out.println("Account: " + response.account());
            System.out.println("ARN: " + response.arn());
            System.out.println("User ID: " + response.userId());
        } catch (Exception e) {
            fail("AWS credential validation failed: " + e.getMessage());
        }
    }
}

