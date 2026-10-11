package com.tcgdigital.vmcontrol.service.aws;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkClient;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.budgets.BudgetsClient;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient;
import software.amazon.awssdk.services.costexplorer.CostExplorerClient;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.eks.EksClient;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.sts.StsClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The one place AWS credentials are resolved and AWS clients are built (E28-T01). Base
 * credentials are the static key pair when both are set, else - with
 * aws.credentials.use-default-chain - the SDK default chain (environment, profile, ECS/EC2
 * instance role, IRSA), else none: AWS features then report unavailable and the app still starts.
 * Clients are cached per service and region and closed on shutdown; callers must not close them.
 */
@Component
public class AwsClientFactory {

    private static final Logger log = LoggerFactory.getLogger(AwsClientFactory.class);

    /** The timeouts every client uses (the values the EC2 provider has always used). */
    static final ClientOverrideConfiguration DEFAULT_OVERRIDES = ClientOverrideConfiguration.builder()
            .apiCallTimeout(Duration.ofSeconds(30))
            .apiCallAttemptTimeout(Duration.ofSeconds(25))
            .build();

    public enum Mode { STATIC_KEYS, DEFAULT_CHAIN, NONE }

    @Value("${aws.access-key:}")
    private String accessKey = "";

    @Value("${aws.secret-key:}")
    private String secretKey = "";

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion = "ap-south-1";

    @Value("${aws.credentials.use-default-chain:false}")
    private boolean useDefaultChain;

    private volatile AwsCredentialsProvider baseCredentials;
    private volatile Mode mode = Mode.NONE;
    private final Map<String, SdkClient> clients = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (notBlank(accessKey) && notBlank(secretKey)) {
            baseCredentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
            mode = Mode.STATIC_KEYS;
        } else if (useDefaultChain) {
            baseCredentials = DefaultCredentialsProvider.create();
            mode = Mode.DEFAULT_CHAIN;
        } else {
            baseCredentials = null;
            mode = Mode.NONE;
        }
        log.info("AWS credentials: {}", switch (mode) {
            case STATIC_KEYS -> "static access key";
            case DEFAULT_CHAIN -> "SDK default credential chain (instance role, profile or environment)";
            case NONE -> "not configured; AWS features are unavailable";
        });
    }

    public boolean isConfigured() {
        return mode != Mode.NONE;
    }

    public Mode mode() {
        return mode;
    }

    public AwsCredentialsProvider baseCredentials() {
        AwsCredentialsProvider credentials = baseCredentials;
        if (credentials == null) {
            throw new IllegalStateException("AWS credentials not configured");
        }
        return credentials;
    }

    public String defaultRegion() {
        return defaultRegion;
    }

    public Ec2Client ec2(String region) {
        Region r = region(region);
        return cached("ec2", r, () -> Ec2Client.builder().region(r).credentialsProvider(baseCredentials())
                .overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    public CloudWatchClient cloudWatch(String region) {
        Region r = region(region);
        return cached("cloudwatch", r, () -> CloudWatchClient.builder().region(r).credentialsProvider(baseCredentials())
                .overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    public EksClient eks(String region) {
        Region r = region(region);
        return cached("eks", r, () -> EksClient.builder().region(r).credentialsProvider(baseCredentials())
                .overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    public ComputeOptimizerClient computeOptimizer(String region) {
        Region r = region(region);
        return cached("compute-optimizer", r, () -> ComputeOptimizerClient.builder().region(r).credentialsProvider(baseCredentials())
                .overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    public AthenaClient athena(String region) {
        Region r = region(region);
        return cached("athena", r, () -> AthenaClient.builder().region(r).credentialsProvider(baseCredentials())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(60)).apiCallAttemptTimeout(Duration.ofSeconds(30)).build())
                .build());
    }

    public StsClient sts(String region) {
        Region r = region(region);
        return cached("sts", r, () -> StsClient.builder().region(r).credentialsProvider(baseCredentials())
                .overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    /** Cost Explorer's API lives in us-east-1 whatever region the account uses. */
    public CostExplorerClient costExplorer() {
        return cached("ce", Region.US_EAST_1, () -> CostExplorerClient.builder().region(Region.US_EAST_1)
                .credentialsProvider(baseCredentials()).overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    /** AWS Budgets is a global service, served from us-east-1. */
    public BudgetsClient budgets() {
        return cached("budgets", Region.US_EAST_1, () -> BudgetsClient.builder().region(Region.US_EAST_1)
                .credentialsProvider(baseCredentials()).overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    /** IAM is a global service. */
    public IamClient iam() {
        return cached("iam", Region.AWS_GLOBAL, () -> IamClient.builder().region(Region.AWS_GLOBAL)
                .credentialsProvider(baseCredentials()).overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    /** Closes every cached client; safe to call more than once. */
    @PreDestroy
    public void closeAll() {
        clients.values().forEach(client -> {
            try {
                client.close();
            } catch (Exception e) {
                log.debug("Closing an AWS client failed: {}", e.getMessage());
            }
        });
        clients.clear();
    }

    @SuppressWarnings("unchecked")
    private <T extends SdkClient> T cached(String service, Region region, Supplier<T> builder) {
        return (T) clients.computeIfAbsent(service + "|" + region.id(), k -> builder.get());
    }

    private Region region(String region) {
        return Region.of(notBlank(region) ? region : defaultRegion);
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}
