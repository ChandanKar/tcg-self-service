package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * AWS starts a stopped RDS database again after seven days (E33-T04). A database the platform
 * stopped carries {@code intended_stopped_since}; when it is found starting or running near the end
 * of those seven days, the auto-start is recorded, audited and its environment's admins are told,
 * and with rds.keep-stopped.enabled it is stopped again once it is available (RDS refuses a stop
 * while it is still starting), which also restarts AWS's seven-day clock. Running earlier than that
 * means someone started it outside the platform: the intent is dropped and nothing is stopped.
 */
@Service
public class RdsKeepStoppedService {

    private static final Logger log = LoggerFactory.getLogger(RdsKeepStoppedService.class);
    /** How long RDS keeps a database stopped before starting it again. */
    public static final Duration AUTO_START_AFTER = Duration.ofDays(7);
    /** An auto-start is expected from this long before the seven days are up. */
    static final Duration AUTO_START_SLACK = Duration.ofHours(2);
    /** No re-stop when a schedule starts the environment this soon anyway. */
    static final Duration SCHEDULED_START_WINDOW = Duration.ofMinutes(60);
    public static final String SOURCE_AUTO_START = "rds_auto_start";
    public static final String SOURCE_RESTOP = "rds_keep_stopped";

    @Value("${rds.keep-stopped.enabled:false}")
    private boolean keepStopped;

    @Autowired(required = false)
    private SettingsService settings;

    @Autowired(required = false)
    private NotificationService notificationService;

    @Autowired(required = false)
    private AutomationRuleService automationRuleService;

    private final VmRepository vmRepository;
    private final RdsCloudProviderService rdsService;
    private final OperationExecutionRepository executionRepository;
    private final StateSyncService stateSyncService;
    private final AuditService auditService;
    private Clock clock = Clock.systemUTC();

    public RdsKeepStoppedService(VmRepository vmRepository, RdsCloudProviderService rdsService,
                                 OperationExecutionRepository executionRepository, StateSyncService stateSyncService,
                                 AuditService auditService) {
        this.vmRepository = vmRepository;
        this.rdsService = rdsService;
        this.executionRepository = executionRepository;
        this.stateSyncService = stateSyncService;
        this.auditService = auditService;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public boolean isKeepStoppedEnabled() {
        return settings == null ? keepStopped : settings.overrideOr("rds.keep-stopped.enabled", keepStopped);
    }

    /** One pass; returns how many databases were stopped again. */
    public int run() {
        if (!rdsService.isAvailable()) {
            return 0;
        }
        List<Vm> vms = vmRepository.findRdsIntendedStopped();
        if (vms.isEmpty()) {
            return 0;
        }
        Set<String> busy = executionRepository.findActiveWithEnvironment(null).stream()
                .map(e -> e.getEnvironment().getEnvironmentId()).collect(Collectors.toSet());
        Map<CloudTarget, List<Vm>> byTarget = vms.stream().collect(Collectors.groupingBy(CloudTarget::of));
        int restopped = 0;
        for (Map.Entry<CloudTarget, List<Vm>> entry : byTarget.entrySet()) {
            Map<String, VmStatus> live;
            try {
                live = rdsService.getVmStatusBatch(entry.getValue().stream().map(Vm::getProviderVmId).toList(), entry.getKey());
            } catch (Exception e) {
                log.warn("RDS keep-stopped status read failed in {}: {}", entry.getKey(), e.getMessage());
                continue;
            }
            for (Vm vm : entry.getValue()) {
                try {
                    if (handle(vm, live.get(vm.getProviderVmId()), busy)) restopped++;
                } catch (Exception e) {
                    log.warn("RDS keep-stopped pass failed for {}: {}", vm.getProviderVmId(), e.getMessage());
                }
            }
        }
        return restopped;
    }

    private boolean handle(Vm vm, VmStatus status, Set<String> busyEnvironments) {
        if (status != VmStatus.STARTING && status != VmStatus.RUNNING) {
            return false;
        }
        Environment env = vm.getGroup().getEnvironment();
        if (busyEnvironments.contains(env.getEnvironmentId())) {
            return false; // a platform operation is running there: it decides
        }
        Instant now = clock.instant();
        Instant expected = vm.getIntendedStoppedSince().toInstant().plus(AUTO_START_AFTER).minus(AUTO_START_SLACK);
        if (vm.getAutoStartDetectedAt() == null && now.isBefore(expected)) {
            // Started outside the platform before AWS would have: someone wants it running.
            vmRepository.clearIntendedStopped(vm.getVmId());
            log.info("RDS {} was started outside the platform; it is no longer kept stopped", vm.getProviderVmId());
            return false;
        }
        boolean keep = isKeepStoppedEnabled();
        if (vm.getAutoStartDetectedAt() == null
                && vmRepository.markAutoStartDetected(vm.getVmId(), Timestamp.from(now)) == 1) {
            stateSyncService.recordStateChange(vm, vm.getStatus(), status, SOURCE_AUTO_START, null, null,
                    "AWS started the stopped database after seven days");
            auditService.logEnvironmentAction(null, AuditAction.RDS_AUTO_START_DETECTED, env.getEnvironmentId(),
                    env.getName(), "VM", vm.getVmId(), vm.getName(),
                    keep ? "AWS restarted it after seven days stopped; it will be stopped again"
                            : "AWS restarted it after seven days stopped; keep-stopped is off, so it keeps running");
            if (notificationService != null) {
                notificationService.notifyRdsAutoStart(env.getEnvironmentId(), env.getName(), vm.getVmId(),
                        vm.getDisplayName() != null ? vm.getDisplayName() : vm.getName(), keep);
            }
        }
        if (!keep || status != VmStatus.RUNNING) {
            return false; // off: recorded only; starting: RDS refuses a stop until it is available
        }
        if (automationRuleService != null && automationRuleService.startsWithin(env.getEnvironmentId(), SCHEDULED_START_WINDOW)) {
            log.info("RDS {} left running: a schedule starts {} within {}", vm.getProviderVmId(), env.getName(), SCHEDULED_START_WINDOW);
            return false;
        }
        CloudProviderService.VmOperationResult result = rdsService.requestStop(vm.getProviderVmId(), CloudTarget.of(vm));
        if (!result.isSuccess()) {
            log.warn("RDS {} could not be stopped again: {}", vm.getProviderVmId(), result.getMessage());
            return false;
        }
        vmRepository.markRestopped(vm.getVmId(), Timestamp.from(now));
        stateSyncService.recordStateChange(vm, VmStatus.RUNNING, VmStatus.STOPPING, SOURCE_RESTOP, null, null,
                "Stopped again after AWS's seven-day automatic start");
        vmRepository.updateStatusIfCurrent(vm.getVmId(), vm.getStatus(), VmStatus.STOPPING, Timestamp.from(now));
        auditService.logEnvironmentAction(null, AuditAction.RDS_RESTOPPED, env.getEnvironmentId(), env.getName(),
                "VM", vm.getVmId(), vm.getName(), "Stopped again after AWS's seven-day automatic start");
        return true;
    }
}
