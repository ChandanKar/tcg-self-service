package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.PurchaseOption;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * On-demand fallback for Spot twins (E31-T06). Every few minutes each running Spot twin's health
 * is read: when it has fewer running nodes than desired, or EKS reports capacity or launch
 * issues, for longer than {@code spot.fallback.grace-minutes}, its on-demand standby is started
 * (restoring its saved size) and the environment's admins are told. Once the twin is healthy at
 * its desired size for two checks in a row, the standby is scaled back to 0. Nothing is done while
 * an operation runs in the environment. Off unless {@code spot.fallback.enabled}.
 */
@Service
public class SpotFallbackService {

    private static final Logger log = LoggerFactory.getLogger(SpotFallbackService.class);
    static final int HEALTHY_CHECKS_TO_CLEAR = 2;

    private final VmRepository vmRepository;
    private final EksCloudProviderService eks;
    private final OperationExecutionRepository executionRepository;
    private final AuditService auditService;
    private NotificationService notificationService;
    private Clock clock = Clock.systemUTC();

    @Value("${spot.fallback.enabled:false}")
    private boolean enabled;

    @Value("${spot.fallback.grace-minutes:10}")
    private int graceMinutes = 10;

    /** An admin's override from Settings (E29-T03), else the configured value; absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    /** Records each fallback as a Spot interruption of the twin (E31-T07); optional in unit tests. */
    private SpotInterruptionRecorder interruptionRecorder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setInterruptionRecorder(SpotInterruptionRecorder interruptionRecorder) {
        this.interruptionRecorder = interruptionRecorder;
    }

    public SpotFallbackService(VmRepository vmRepository, EksCloudProviderService eks,
                               OperationExecutionRepository executionRepository, AuditService auditService) {
        this.vmRepository = vmRepository;
        this.eks = eks;
        this.executionRepository = executionRepository;
        this.auditService = auditService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public boolean isEnabled() {
        return settings == null ? enabled : settings.overrideOr("spot.fallback.enabled", enabled);
    }

    private int grace() {
        return settings == null ? graceMinutes : settings.overrideOr("spot.fallback.grace-minutes", graceMinutes);
    }

    /** One pass over every running Spot twin; returns how many fallbacks were activated or cleared. */
    public int evaluate() {
        if (!isEnabled()) {
            return 0;
        }
        int changes = 0;
        List<Vm> twins = vmRepository.findByIsActiveTrueFetchGroupAndEnvironment().stream()
                .filter(v -> v.getProvider() == CloudProvider.AWS_EKS && v.getPurchaseOption() == PurchaseOption.SPOT
                        && v.getSpotPartnerVmId() != null && !v.isStandby())
                .toList();
        for (Vm twin : twins) {
            try {
                changes += evaluate(twin) ? 1 : 0;
            } catch (RuntimeException e) {
                log.warn("Spot fallback check of {} failed: {}", twin.getProviderVmId(), e.getMessage());
            }
        }
        return changes;
    }

    private boolean evaluate(Vm twin) {
        Environment env = twin.getGroup().getEnvironment();
        if (twin.getStatus() != VmStatus.RUNNING && !twin.isSpotFallbackActive()) {
            return false; // the environment is stopped: nothing should run
        }
        if (executionRepository.hasActiveOperations(env.getEnvironmentId())) {
            return false;
        }
        String[] parts = twin.getProviderVmId().split("/", 2);
        CloudTarget target = CloudTarget.of(twin);
        EksCloudProviderService.NodegroupHealth health = eks.getNodegroupHealth(target, parts[0], parts[1]);
        Instant now = clock.instant();
        if (degraded(health)) {
            Timestamp since = twin.getSpotDegradedSince() != null ? twin.getSpotDegradedSince() : Timestamp.from(now);
            boolean due = !twin.isSpotFallbackActive()
                    && Duration.between(since.toInstant(), now).compareTo(Duration.ofMinutes(grace())) >= 0;
            if (!due) {
                vmRepository.updateSpotFallbackState(twin.getVmId(), since, twin.isSpotFallbackActive(), 0);
                return false;
            }
            Vm standby = vmRepository.findById(twin.getSpotPartnerVmId()).orElse(null);
            if (standby == null) {
                return false;
            }
            CloudProviderService.VmOperationResult start = eks.startVm(standby.getProviderVmId(), CloudTarget.of(standby)).join();
            if (!start.isSuccess()) {
                log.warn("Spot fallback: standby {} did not start: {}", standby.getProviderVmId(), start.getMessage());
                vmRepository.updateSpotFallbackState(twin.getVmId(), since, false, 0);
                return false;
            }
            vmRepository.updateSpotFallbackState(twin.getVmId(), since, true, 0);
            vmRepository.updateStatusIfCurrent(standby.getVmId(), standby.getStatus(), VmStatus.RUNNING, Timestamp.from(now));
            String detail = describe(health);
            auditService.logEnvironmentAction(null, AuditAction.SPOT_FALLBACK_ACTIVATED, env.getEnvironmentId(), env.getDisplayName(),
                    "vm", twin.getVmId(), twin.getDisplayName(), "Started on-demand " + standby.getDisplayName() + ": " + detail);
            if (interruptionRecorder != null) {
                interruptionRecorder.record(twin.getVmId(), twin.getDisplayName(), env.getEnvironmentId(), env.getDisplayName(),
                        twin.getStatus(), twin.getStatus(), "Spot capacity lost (" + detail + "); on-demand standby started");
            }
            if (notificationService != null) {
                notificationService.notifySpotFallback(env.getEnvironmentId(), twin.getVmId(), true,
                        "Spot capacity lost: " + twin.getDisplayName(),
                        twin.getDisplayName() + " is short of Spot capacity (" + detail + "), so its on-demand node group "
                                + standby.getDisplayName() + " was started.");
            }
            return true;
        }
        // healthy
        if (!twin.isSpotFallbackActive()) {
            if (twin.getSpotDegradedSince() != null) {
                vmRepository.updateSpotFallbackState(twin.getVmId(), null, false, 0);
            }
            return false;
        }
        int healthy = twin.getSpotHealthyChecks() + 1;
        if (healthy < HEALTHY_CHECKS_TO_CLEAR) {
            vmRepository.updateSpotFallbackState(twin.getVmId(), null, true, healthy);
            return false;
        }
        Vm standby = vmRepository.findById(twin.getSpotPartnerVmId()).orElse(null);
        if (standby != null) {
            CloudProviderService.VmOperationResult stop = eks.stopVm(standby.getProviderVmId(), CloudTarget.of(standby), false).join();
            if (!stop.isSuccess()) {
                log.warn("Spot fallback: standby {} did not stop: {}", standby.getProviderVmId(), stop.getMessage());
                vmRepository.updateSpotFallbackState(twin.getVmId(), null, true, healthy);
                return false;
            }
            vmRepository.updateStatusIfCurrent(standby.getVmId(), standby.getStatus(), VmStatus.STOPPED, Timestamp.from(now));
        }
        vmRepository.updateSpotFallbackState(twin.getVmId(), null, false, 0);
        auditService.logEnvironmentAction(null, AuditAction.SPOT_FALLBACK_CLEARED, env.getEnvironmentId(), env.getDisplayName(),
                "vm", twin.getVmId(), twin.getDisplayName(), "Spot capacity is back; on-demand standby scaled to 0");
        if (notificationService != null) {
            notificationService.notifySpotFallback(env.getEnvironmentId(), twin.getVmId(), false,
                    "Spot capacity back: " + twin.getDisplayName(),
                    twin.getDisplayName() + " runs at its full size on Spot again; the on-demand node group is back at 0.");
        }
        return true;
    }

    /** Fewer running nodes than desired, or a capacity or launch issue reported by EKS. */
    static boolean degraded(EksCloudProviderService.NodegroupHealth health) {
        if (health.desired() > 0 && health.running() < health.desired()) {
            return true;
        }
        return health.issueCodes().stream().map(c -> c.toLowerCase(Locale.ROOT))
                .anyMatch(c -> c.contains("capacity") || c.contains("launchfailure") || c.contains("insufficientfreeaddresses"));
    }

    private static String describe(EksCloudProviderService.NodegroupHealth health) {
        String nodes = health.running() + " of " + health.desired() + " nodes running";
        return health.issueCodes().isEmpty() ? nodes : nodes + ", " + String.join(", ", health.issueCodes());
    }
}
