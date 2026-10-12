package com.tcgdigital.vmcontrol.service.aws;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.tcgdigital.vmcontrol.model.AwsAccount;
import org.springframework.beans.factory.ObjectProvider;
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
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.rds.RdsClient;
import software.amazon.awssdk.services.servicecatalog.ServiceCatalogClient;
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
 * Clients are cached per service, account and region (E28-T04: member accounts use AssumeRole
 * credentials from {@link AwsAccountCredentialsResolver}) and closed on shutdown; callers must not
 * close them.
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
    /** Looked up lazily: the resolver itself depends on this factory (E28-T04). */
    private ObjectProvider<AwsAccountCredentialsResolver> resolverProvider;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setResolverProvider(ObjectProvider<AwsAccountCredentialsResolver> resolverProvider) {
        this.resolverProvider = resolverProvider;
    }

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
        return ec2(AwsAccount.DEFAULT_REF, region);
    }

    /** An account's EC2 client in a region (E28-T04); a null ref is the default account. */
    public Ec2Client ec2(String accountRef, String region) {
        Region r = region(region);
        return cached("ec2", accountRef, r, () -> Ec2Client.builder().region(r).credentialsProvider(credentials(accountRef))
                .overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    public CloudWatchClient cloudWatch(String region) {
        return cloudWatch(AwsAccount.DEFAULT_REF, region);
    }

    public CloudWatchClient cloudWatch(String accountRef, String region) {
        Region r = region(region);
        return cached("cloudwatch", accountRef, r, () -> CloudWatchClient.builder().region(r).credentialsProvider(credentials(accountRef))
                .overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    public EksClient eks(String region) {
        return eks(AwsAccount.DEFAULT_REF, region);
    }

    public EksClient eks(String accountRef, String region) {
        Region r = region(region);
        return cached("eks", accountRef, r, () -> EksClient.builder().region(r).credentialsProvider(credentials(accountRef))
                .overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    public RdsClient rds(String accountRef, String region) {
        Region r = region(region);
        return cached("rds", accountRef, r, () -> RdsClient.builder().region(r).credentialsProvider(credentials(accountRef))
                .overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    public ComputeOptimizerClient computeOptimizer(String region) {
        return computeOptimizer(AwsAccount.DEFAULT_REF, region);
    }

    public ComputeOptimizerClient computeOptimizer(String accountRef, String region) {
        Region r = region(region);
        return cached("compute-optimizer", accountRef, r, () -> ComputeOptimizerClient.builder().region(r)
                .credentialsProvider(credentials(accountRef)).overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    /** Service Catalog in an account and region (E32-T02); a null account is the default one. */
    public ServiceCatalogClient serviceCatalog(String accountRef, String region) {
        String ref = accountRef == null || accountRef.isBlank() ? AwsAccount.DEFAULT_REF : accountRef;
        Region r = region(region);
        return cached("service-catalog", ref, r, () -> ServiceCatalogClient.builder().region(r)
                .credentialsProvider(credentials(ref)).overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    public AthenaClient athena(String region) {
        Region r = region(region);
        return cached("athena", AwsAccount.DEFAULT_REF, r, () -> AthenaClient.builder().region(r).credentialsProvider(baseCredentials())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(60)).apiCallAttemptTimeout(Duration.ofSeconds(30)).build())
                .build());
    }

    /** STS with the base identity: the client every AssumeRole goes through. */
    public StsClient sts(String region) {
        return sts(AwsAccount.DEFAULT_REF, region);
    }

    public StsClient sts(String accountRef, String region) {
        Region r = region(region);
        return cached("sts", accountRef, r, () -> StsClient.builder().region(r).credentialsProvider(credentials(accountRef))
                .overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    /** Cost Explorer's API lives in us-east-1 whatever region the account uses. */
    public CostExplorerClient costExplorer() {
        return costExplorer(AwsAccount.DEFAULT_REF);
    }

    public CostExplorerClient costExplorer(String accountRef) {
        return cached("ce", accountRef, Region.US_EAST_1, () -> CostExplorerClient.builder().region(Region.US_EAST_1)
                .credentialsProvider(credentials(accountRef)).overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    /** AWS Budgets is a global service, served from us-east-1. */
    public BudgetsClient budgets() {
        return cached("budgets", AwsAccount.DEFAULT_REF, Region.US_EAST_1, () -> BudgetsClient.builder().region(Region.US_EAST_1)
                .credentialsProvider(baseCredentials()).overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    /** AWS Organizations, read with the billing (management or delegated-admin) account (E28-T08). */
    public OrganizationsClient organizations(String accountRef) {
        return cached("organizations", accountRef, Region.US_EAST_1, () -> OrganizationsClient.builder().region(Region.US_EAST_1)
                .credentialsProvider(credentials(accountRef)).overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    /** IAM is a global service. */
    public IamClient iam() {
        return cached("iam", AwsAccount.DEFAULT_REF, Region.AWS_GLOBAL, () -> IamClient.builder().region(Region.AWS_GLOBAL)
                .credentialsProvider(baseCredentials()).overrideConfiguration(DEFAULT_OVERRIDES).build());
    }

    /**
     * Closes and forgets every client of an account and its assumed-role credentials (E28-T04),
     * after the account was changed or deactivated; the next request builds them afresh.
     */
    public void evict(String accountRef) {
        String marker = "|" + refKey(accountRef) + "|";
        clients.keySet().stream().filter(k -> k.contains(marker)).toList().forEach(k -> {
            SdkClient client = clients.remove(k);
            if (client != null) {
                try {
                    client.close();
                } catch (Exception e) {
                    log.debug("Closing an AWS client failed: {}", e.getMessage());
                }
            }
        });
        AwsAccountCredentialsResolver resolver = resolver();
        if (resolver != null) {
            resolver.evict(refKey(accountRef));
        }
    }

    /** The default account's credentials are the base ones; others come from the resolver (AssumeRole). */
    private AwsCredentialsProvider credentials(String accountRef) {
        String ref = refKey(accountRef);
        AwsAccountCredentialsResolver resolver = resolver();
        if (AwsAccount.DEFAULT_REF.equals(ref) || resolver == null) {
            return baseCredentials();
        }
        return resolver.forAccount(ref);
    }

    private AwsAccountCredentialsResolver resolver() {
        return resolverProvider == null ? null : resolverProvider.getIfAvailable();
    }

    private static String refKey(String accountRef) {
        return accountRef == null || accountRef.isBlank() ? AwsAccount.DEFAULT_REF : accountRef;
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
    private <T extends SdkClient> T cached(String service, String accountRef, Region region, Supplier<T> builder) {
        return (T) clients.computeIfAbsent(service + "|" + refKey(accountRef) + "|" + region.id(), k -> builder.get());
    }

    private Region region(String region) {
        return Region.of(notBlank(region) ? region : defaultRegion);
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }
}
