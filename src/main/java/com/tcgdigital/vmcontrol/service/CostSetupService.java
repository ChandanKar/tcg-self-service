package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CostSetupStatusDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.ScheduledJobLock;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.ScheduledJobLockRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.EvaluationResult;
import software.amazon.awssdk.services.iam.model.PolicyEvaluationDecisionType;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyRequest;
import software.amazon.awssdk.services.iam.model.SimulatePrincipalPolicyResponse;
import software.amazon.awssdk.services.sts.StsClient;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Cost Setup (E08-T11): which cost features are on, when each last ran, and, on request, a
 * read-only pre-flight check of the IAM permissions they need. {@link #getStatus()} makes no AWS
 * call. {@link #runChecks()} asks STS who we are, simulates the needed actions with IAM, and runs
 * free probes (Compute Optimizer enrollment, an EC2 CreateTags dry run, and one billed
 * ListCostAllocationTags call); it never calls GetCostAndUsage. A check is reused for 60 s.
 */
@Service
public class CostSetupService {

    private static final Logger log = LoggerFactory.getLogger(CostSetupService.class);

    static final String ALLOWED = "ALLOWED";
    static final String DENIED = "DENIED";
    static final String UNKNOWN = "UNKNOWN";
    static final List<String> TAG_KEYS = List.of("tcg:environment", "tcg:team", "tcg:managed-by");
    private static final Duration CHECK_CACHE = Duration.ofSeconds(60);

    /** A feature row: its switch, its job lock (for the last run) and the actions it needs. */
    private record FeatureDef(String key, String label, String property, String envVar, String lockName,
                              List<String> actions) {
    }

    private static final List<FeatureDef> FEATURES = List.of(
            new FeatureDef("snapshot", "Estimated cost snapshots", "cost.snapshot.enabled", "COST_SNAPSHOT_ENABLED",
                    "cost_snapshot", List.of()),
            new FeatureDef("actuals", "Actual costs (Cost Explorer)", "cost.actuals.enabled", "COST_ACTUALS_ENABLED",
                    "actual_cost_ingestion", List.of("ce:GetCostAndUsage")),
            new FeatureDef("tagging", "Cost-allocation tagging", "cost.tagging.enabled", "COST_TAGGING_ENABLED",
                    "tag_reconciliation", List.of("ec2:CreateTags", "eks:TagResource", "ce:ListCostAllocationTags",
                            "ce:UpdateCostAllocationTagsStatus")),
            new FeatureDef("optimizer", "Compute Optimizer", "cost.optimizer.enabled", "COST_OPTIMIZER_ENABLED",
                    null, List.of("compute-optimizer:GetEC2InstanceRecommendations", "compute-optimizer:GetEnrollmentStatus")),
            new FeatureDef("reservations", "RI / Savings Plan coverage", "cost.reservations.enabled", "COST_RESERVATIONS_ENABLED",
                    "reservation_coverage_snapshot", List.of("ce:GetReservationCoverage", "ce:GetReservationUtilization",
                            "ce:GetSavingsPlansCoverage", "ce:GetSavingsPlansUtilization")),
            new FeatureDef("weeklyReports", "Weekly reports", "notification.weekly-reports.enabled",
                    "NOTIFICATION_WEEKLY_REPORTS_ENABLED", "weekly_notification_reports", List.of()));

    private final ScheduledJobLockRepository jobLockRepository;
    private final VmRepository vmRepository;
    private final CostExplorerClientProvider clientProvider;
    private final ComputeOptimizerService computeOptimizerService;
    private final AwsCloudProviderService awsCloudProviderService;
    private final CostExplorerTagActivationService tagActivationService;
    private final StsClient stsClient;
    private final IamClient iamClient;
    private final Map<String, Boolean> enabled;
    private final String defaultRegion;
    private final Clock clock;

    private volatile CostSetupStatusDTO lastCheck;

    @org.springframework.beans.factory.annotation.Autowired
    public CostSetupService(ScheduledJobLockRepository jobLockRepository, VmRepository vmRepository,
                            CostExplorerClientProvider clientProvider, ComputeOptimizerService computeOptimizerService,
                            AwsCloudProviderService awsCloudProviderService,
                            CostExplorerTagActivationService tagActivationService,
                            StsClient stsClient, IamClient iamClient,
                            @Value("${cost.snapshot.enabled:true}") boolean snapshot,
                            @Value("${cost.actuals.enabled:true}") boolean actuals,
                            @Value("${cost.tagging.enabled:false}") boolean tagging,
                            @Value("${cost.optimizer.enabled:true}") boolean optimizer,
                            @Value("${cost.reservations.enabled:true}") boolean reservations,
                            @Value("${notification.weekly-reports.enabled:true}") boolean weeklyReports,
                            @Value("${aws.region:us-east-1}") String defaultRegion) {
        this(jobLockRepository, vmRepository, clientProvider, computeOptimizerService, awsCloudProviderService,
                tagActivationService, stsClient, iamClient,
                Map.of("snapshot", snapshot, "actuals", actuals, "tagging", tagging, "optimizer", optimizer,
                        "reservations", reservations, "weeklyReports", weeklyReports),
                defaultRegion, Clock.systemUTC());
    }

    CostSetupService(ScheduledJobLockRepository jobLockRepository, VmRepository vmRepository,
                     CostExplorerClientProvider clientProvider, ComputeOptimizerService computeOptimizerService,
                     AwsCloudProviderService awsCloudProviderService,
                     CostExplorerTagActivationService tagActivationService,
                     StsClient stsClient, IamClient iamClient, Map<String, Boolean> enabled,
                     String defaultRegion, Clock clock) {
        this.jobLockRepository = jobLockRepository;
        this.vmRepository = vmRepository;
        this.clientProvider = clientProvider;
        this.computeOptimizerService = computeOptimizerService;
        this.awsCloudProviderService = awsCloudProviderService;
        this.tagActivationService = tagActivationService;
        this.stsClient = stsClient;
        this.iamClient = iamClient;
        this.enabled = enabled;
        this.defaultRegion = defaultRegion;
        this.clock = clock;
    }

    /** Switches, last runs and tagging counts; never calls AWS. */
    public CostSetupStatusDTO getStatus() {
        return new CostSetupStatusDTO(clientProvider.isConfigured(), null, null, features(Map.of()), List.of());
    }

    /** The pre-flight check, reused for 60 s so repeated clicks don't repeat the calls. */
    public synchronized CostSetupStatusDTO runChecks() {
        CostSetupStatusDTO cached = lastCheck;
        if (cached != null && cached.checkedAt().toInstant().plus(CHECK_CACHE).isAfter(clock.instant())) {
            return cached;
        }
        CostSetupStatusDTO result = clientProvider.isConfigured() ? check() : notConfigured();
        lastCheck = result;
        return result;
    }

    private CostSetupStatusDTO notConfigured() {
        Map<String, CostSetupStatusDTO.Check> checks = new LinkedHashMap<>();
        allActions().forEach(a -> checks.put(a, new CostSetupStatusDTO.Check(a, UNKNOWN,
                "AWS credentials are not configured (aws.access-key / aws.secret-key)")));
        return new CostSetupStatusDTO(false, null, Timestamp.from(clock.instant()), features(checks), List.of());
    }

    private CostSetupStatusDTO check() {
        String principalArn = null;
        Map<String, CostSetupStatusDTO.Check> checks = new LinkedHashMap<>();
        try {
            principalArn = stsClient.getCallerIdentity().arn();
        } catch (Exception e) {
            log.warn("Cost setup: GetCallerIdentity failed: {}", e.getMessage());
        }
        if (principalArn == null) {
            allActions().forEach(a -> checks.put(a, new CostSetupStatusDTO.Check(a, UNKNOWN,
                    "Could not identify the AWS principal (sts:GetCallerIdentity)")));
        } else {
            checks.putAll(simulate(policySourceArn(principalArn)));
        }
        List<CostSetupStatusDTO.Probe> probes = new ArrayList<>();
        probes.add(enrollmentProbe());
        probes.addAll(tagDryRunProbes());
        probes.add(tagKeyProbe());
        return new CostSetupStatusDTO(true, principalArn, Timestamp.from(clock.instant()), features(checks), probes);
    }

    /** Simulate every action; a refused simulation leaves them UNKNOWN with a hint. */
    private Map<String, CostSetupStatusDTO.Check> simulate(String policySourceArn) {
        Map<String, CostSetupStatusDTO.Check> checks = new LinkedHashMap<>();
        List<String> actions = allActions();
        try {
            String marker = null;
            do {
                SimulatePrincipalPolicyResponse page = iamClient.simulatePrincipalPolicy(SimulatePrincipalPolicyRequest.builder()
                        .policySourceArn(policySourceArn).actionNames(actions).marker(marker).build());
                for (EvaluationResult r : page.evaluationResults()) {
                    boolean allowed = r.evalDecision() == PolicyEvaluationDecisionType.ALLOWED;
                    checks.put(r.evalActionName(), new CostSetupStatusDTO.Check(r.evalActionName(),
                            allowed ? ALLOWED : DENIED,
                            allowed ? null : "Grant " + r.evalActionName() + " (see the policy snippet)"));
                }
                marker = Boolean.TRUE.equals(page.isTruncated()) ? page.marker() : null;
            } while (marker != null);
        } catch (Exception e) {
            log.warn("Cost setup: SimulatePrincipalPolicy failed: {}", e.getMessage());
            actions.forEach(a -> checks.put(a, new CostSetupStatusDTO.Check(a, UNKNOWN,
                    "Grant iam:SimulatePrincipalPolicy or verify manually")));
        }
        actions.forEach(a -> checks.putIfAbsent(a, new CostSetupStatusDTO.Check(a, UNKNOWN, "Not evaluated")));
        return checks;
    }

    /** An assumed-role session ARN is simulated as its role. */
    static String policySourceArn(String callerArn) {
        // arn:aws:sts::123456789012:assumed-role/RoleName/session -> arn:aws:iam::123456789012:role/RoleName
        if (callerArn.contains(":assumed-role/")) {
            String[] parts = callerArn.split(":", 6);
            String[] path = parts[5].split("/");
            return "arn:" + parts[1] + ":iam::" + parts[4] + ":role/" + path[1];
        }
        return callerArn;
    }

    private CostSetupStatusDTO.Probe enrollmentProbe() {
        String region = managedRegions().stream().findFirst().orElse(defaultRegion);
        try {
            String status = computeOptimizerService.getEnrollmentStatus(region);
            return new CostSetupStatusDTO.Probe("Compute Optimizer enrollment", "Active".equalsIgnoreCase(status) ? ALLOWED : DENIED,
                    status + " in " + region + ("Active".equalsIgnoreCase(status) ? "" : " — opt in from the AWS console"));
        } catch (Exception e) {
            return new CostSetupStatusDTO.Probe("Compute Optimizer enrollment", UNKNOWN, e.getMessage());
        }
    }

    /** One CreateTags dry run per region with a managed AWS instance. */
    private List<CostSetupStatusDTO.Probe> tagDryRunProbes() {
        Map<String, String> instanceByRegion = new LinkedHashMap<>();
        for (Vm vm : vmRepository.findByIsActiveTrue()) {
            if (vm.getProvider() == CloudProvider.AWS && vm.getProviderVmId() != null && vm.getRegion() != null) {
                instanceByRegion.putIfAbsent(vm.getRegion(), vm.getProviderVmId());
            }
        }
        List<CostSetupStatusDTO.Probe> probes = new ArrayList<>();
        instanceByRegion.forEach((region, instanceId) -> {
            String name = "EC2 tag write (dry run) in " + region;
            try {
                boolean allowed = awsCloudProviderService.canTagInstance(region, instanceId);
                probes.add(new CostSetupStatusDTO.Probe(name, allowed ? ALLOWED : DENIED,
                        allowed ? "ec2:CreateTags allowed" : "ec2:CreateTags denied"));
            } catch (Exception e) {
                probes.add(new CostSetupStatusDTO.Probe(name, UNKNOWN, e.getMessage()));
            }
        });
        return probes;
    }

    private CostSetupStatusDTO.Probe tagKeyProbe() {
        String name = "Cost-allocation tag keys (1 billed Cost Explorer call)";
        try {
            Map<String, String> status = tagActivationService.tagKeyStatus(TAG_KEYS);
            boolean allActive = status.values().stream().allMatch("Active"::equalsIgnoreCase);
            return new CostSetupStatusDTO.Probe(name, allActive ? ALLOWED : DENIED, status.toString());
        } catch (Exception e) {
            return new CostSetupStatusDTO.Probe(name, UNKNOWN, e.getMessage());
        }
    }

    private List<String> managedRegions() {
        LinkedHashSet<String> regions = new LinkedHashSet<>();
        for (Vm vm : vmRepository.findByIsActiveTrue()) {
            if (vm.getProvider() == CloudProvider.AWS && vm.getRegion() != null) {
                regions.add(vm.getRegion());
            }
        }
        return new ArrayList<>(regions);
    }

    private static List<String> allActions() {
        return FEATURES.stream().flatMap(f -> f.actions().stream()).distinct().toList();
    }

    private List<CostSetupStatusDTO.Feature> features(Map<String, CostSetupStatusDTO.Check> checks) {
        List<CostSetupStatusDTO.Feature> features = new ArrayList<>();
        for (FeatureDef def : FEATURES) {
            Timestamp lastRun = def.lockName() == null ? null
                    : jobLockRepository.findById(def.lockName()).map(ScheduledJobLock::getAcquiredAt).orElse(null);
            Map<String, Object> extra = new LinkedHashMap<>();
            if (def.key().equals("tagging")) {
                extra.put("taggedVmCount", vmRepository.countByIsActiveTrueAndTagsSyncedAtIsNotNull());
                extra.put("untaggedVmCount", vmRepository.countByIsActiveTrueAndTagsSyncedAtIsNull());
            }
            List<CostSetupStatusDTO.Check> featureChecks = checks.isEmpty() ? List.of()
                    : def.actions().stream().map(checks::get).toList();
            features.add(new CostSetupStatusDTO.Feature(def.key(), def.label(), Boolean.TRUE.equals(enabled.get(def.key())),
                    def.property(), def.envVar(), lastRun, extra, featureChecks));
        }
        return features;
    }
}
