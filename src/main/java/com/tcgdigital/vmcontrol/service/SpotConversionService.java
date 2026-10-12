package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.PurchaseOption;
import com.tcgdigital.vmcontrol.model.SpotPolicy;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.eks.model.Nodegroup;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Converting an EKS node group to Spot (E31-T05). A managed node group's capacity type is fixed
 * when it is created, so a SPOT twin with diversified same-size instance types is created; once
 * it is ready the on-demand original is scaled to 0 and kept as its standby (not started with the
 * environment). If the twin never becomes ready it is deleted and the original keeps running.
 * Going back starts the standby, then stops and deletes the twin.
 *
 * Behind {@code spot.enabled} and {@code spot.eks.conversion.enabled} (both off); the environment
 * must allow Spot and the caller must hold its lock with nothing else running.
 */
@Service
public class SpotConversionService {

    private static final Logger log = LoggerFactory.getLogger(SpotConversionService.class);

    private final VmRepository vmRepository;
    private final VmGroupRepository groupRepository;
    private final EksCloudProviderService eks;
    private final EksSyncService eksSync;
    private final SpotPolicyService spotPolicy;
    private final SecurityService securityService;
    private final LockService lockService;
    private final OperationExecutionRepository executionRepository;
    private final PricingReferenceService pricing;
    private final AuditService auditService;

    @Value("${spot.eks.conversion.enabled:false}")
    private boolean conversionEnabled;

    @Value("${spot.eks.min-instance-types:3}")
    private int minInstanceTypes = 3;

    /** An admin's override from Settings (E29-T03), else the configured value; absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    private NotificationService notificationService;
    /** Runs the work after the request commits; directly in unit tests. */
    private com.tcgdigital.vmcontrol.service.support.AfterCommit afterCommit;
    /** Where the work runs; the caller's thread in unit tests. */
    private java.util.concurrent.Executor executor = Runnable::run;

    public SpotConversionService(VmRepository vmRepository, VmGroupRepository groupRepository, EksCloudProviderService eks,
                                 EksSyncService eksSync, SpotPolicyService spotPolicy, SecurityService securityService,
                                 LockService lockService, OperationExecutionRepository executionRepository,
                                 PricingReferenceService pricing, AuditService auditService) {
        this.vmRepository = vmRepository;
        this.groupRepository = groupRepository;
        this.eks = eks;
        this.eksSync = eksSync;
        this.spotPolicy = spotPolicy;
        this.securityService = securityService;
        this.lockService = lockService;
        this.executionRepository = executionRepository;
        this.pricing = pricing;
        this.auditService = auditService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAfterCommit(com.tcgdigital.vmcontrol.service.support.AfterCommit afterCommit) {
        this.afterCommit = afterCommit;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setExecutor(@org.springframework.beans.factory.annotation.Qualifier("eksOperationExecutor") java.util.concurrent.Executor executor) {
        this.executor = executor;
    }

    boolean conversionEnabled() {
        return settings == null ? conversionEnabled : settings.overrideOr("spot.eks.conversion.enabled", conversionEnabled);
    }

    private int minTypes() {
        return settings == null ? minInstanceTypes : settings.overrideOr("spot.eks.min-instance-types", minInstanceTypes);
    }

    /** What a conversion needs, captured in the request so the background work never touches lazy entities. */
    private record Job(String environmentId, String environmentName, String vmId, String vmName, String cluster,
                       String nodegroup, CloudTarget target, String region, String userId) {
    }

    private Vm checkedNodeGroup(String environmentId, String vmId, String userId) {
        if (!spotPolicy.isEnabled() || !conversionEnabled()) {
            throw new ValidationException("Converting node groups to Spot is turned off (spot.eks.conversion.enabled)");
        }
        securityService.assertCanAdminister(environmentId);
        Vm vm = vmRepository.findById(vmId).orElseThrow(() -> new ResourceNotFoundException("VM", vmId));
        securityService.assertSameEnvironment(vm.getGroup().getEnvironment().getEnvironmentId(), environmentId);
        if (vm.getProvider() != CloudProvider.AWS_EKS || vm.getProviderVmId() == null || !vm.getProviderVmId().contains("/")) {
            throw new ValidationException("Only EKS node groups can be converted to Spot");
        }
        lockService.verifyLockPermission(environmentId, userId);
        if (executionRepository.hasActiveOperations(environmentId)) {
            throw new ValidationException("An operation is running in this environment");
        }
        return vm;
    }

    private Job job(Vm vm, String userId) {
        Environment env = vm.getGroup().getEnvironment();
        String[] parts = vm.getProviderVmId().split("/", 2);
        return new Job(env.getEnvironmentId(), env.getDisplayName(), vm.getVmId(), vm.getDisplayName(), parts[0], parts[1],
                CloudTarget.of(vm), vm.getRegion(), userId);
    }

    private void later(Runnable work) {
        Runnable launch = () -> executor.execute(work);
        if (afterCommit != null) {
            afterCommit.run(launch);
        } else {
            launch.run();
        }
    }

    /** Checks now (400/403/404 on failure), then converts in the background. */
    public void convertToSpot(String environmentId, String vmId, String userId) {
        Vm vm = checkedNodeGroup(environmentId, vmId, userId);
        Environment env = vm.getGroup().getEnvironment();
        if (env.getSpotPolicy() != SpotPolicy.ALLOWED) {
            throw new ValidationException("This environment does not allow Spot");
        }
        if (vm.isSpotExcluded()) {
            throw new ValidationException("This node group is excluded from Spot");
        }
        if (vm.getPurchaseOption() == PurchaseOption.SPOT || vm.isStandby() || vm.getSpotPartnerVmId() != null) {
            throw new ValidationException("This node group is already converted");
        }
        if (vm.getStatus() != VmStatus.RUNNING) {
            throw new ValidationException("The node group must be running to be converted");
        }
        String type = EstimatedCostProvider.eksNodeInfo(vm.getMetadata()).instanceType();
        List<String> types = pricing.sameShapeTypes(type);
        if (type == null || types.size() < minTypes()) {
            throw new ValidationException("Spot needs at least " + minTypes() + " instance types of the same size; "
                    + (type == null ? "the node group's type is not known" : type + " has " + types.size()));
        }
        Job job = job(vm, userId);
        auditService.logEnvironmentAction(userId, AuditAction.SPOT_CONVERSION_STARTED, job.environmentId(), job.environmentName(),
                "vm", vmId, job.vmName(), "Creating a Spot node group with " + String.join(", ", types));
        later(() -> runConversion(job, types));
    }

    void runConversion(Job job, List<String> types) {
        String twin = EksCloudProviderService.spotTwinName(job.nodegroup());
        try {
            eks.createSpotTwin(job.target(), job.cluster(), job.nodegroup(), types);
        } catch (RuntimeException e) {
            log.warn("Spot twin of {}/{} failed: {}", job.cluster(), job.nodegroup(), e.getMessage());
            try {
                eks.deleteNodegroup(job.target(), job.cluster(), twin);
            } catch (RuntimeException cleanup) {
                log.warn("Could not delete the failed Spot node group {}/{}: {}", job.cluster(), twin, cleanup.getMessage());
            }
            failed(job, "The Spot node group did not become ready: " + e.getMessage() + ". The on-demand node group keeps running.");
            return;
        }
        try {
            Vm source = vmRepository.findById(job.vmId()).orElseThrow();
            Environment env = source.getGroup().getEnvironment();
            Nodegroup twinGroup = eks.describeNodegroup(job.cluster(), twin, job.target());
            Integer max = groupRepository.findMaxSequencePositionByEnvironmentId(job.environmentId());
            eksSync.upsertNodeGroup(env, job.cluster(), twin, job.region(), twinGroup, new AtomicInteger((max == null ? 0 : max) + 1));
            Vm twinVm = vmRepository.findByProviderAndProviderVmId(CloudProvider.AWS_EKS, job.cluster() + "/" + twin).orElseThrow();

            CloudProviderService.VmOperationResult stop = eks.stopVm(job.cluster() + "/" + job.nodegroup(), job.target(), false).join();
            if (!stop.isSuccess()) {
                link(source, twinVm, false);
                failed(job, "The Spot node group runs, but the on-demand one could not be scaled to 0: " + stop.getMessage()
                        + ". Both are running.");
                return;
            }
            link(vmRepository.findById(job.vmId()).orElseThrow(), twinVm, true);
            auditService.logEnvironmentAction(job.userId(), AuditAction.SPOT_CONVERSION_COMPLETED, job.environmentId(),
                    job.environmentName(), "vm", job.vmId(), job.vmName(), "Now on Spot node group " + twin + "; on-demand standby at 0");
            if (notificationService != null) {
                notificationService.notifySpotOutcome(job.userId(), job.vmId(), true, "On Spot: " + job.vmName(),
                        job.vmName() + " now runs on the Spot node group " + twin + "; the on-demand node group is kept at 0 as standby.");
            }
        } catch (RuntimeException e) {
            log.error("Spot conversion of {}/{} failed after the twin was created", job.cluster(), job.nodegroup(), e);
            failed(job, "The Spot node group " + twin + " was created but could not be registered: " + e.getMessage());
        }
    }

    /** Points the two node groups at each other; the original becomes a standby once it is at 0. */
    private void link(Vm source, Vm twin, boolean standby) {
        source.setSpotPartnerVmId(twin.getVmId());
        source.setStandby(standby);
        if (standby) {
            source.setStatus(VmStatus.STOPPED);
            source.setLastStateSyncAt(Timestamp.from(Instant.now()));
        }
        twin.setSpotPartnerVmId(source.getVmId());
        twin.setPurchaseOption(PurchaseOption.SPOT);
        vmRepository.save(source);
        vmRepository.save(twin);
    }

    /** Starts the on-demand standby again, then stops and deletes the Spot twin (in the background). */
    public void revertToOnDemand(String environmentId, String vmId, String userId) {
        Vm vm = checkedNodeGroup(environmentId, vmId, userId);
        Vm standby = vm.isStandby() ? vm
                : vm.getSpotPartnerVmId() == null ? null : vmRepository.findById(vm.getSpotPartnerVmId()).orElse(null);
        if (standby == null || !standby.isStandby() || standby.getSpotPartnerVmId() == null) {
            throw new ValidationException("This node group has no on-demand standby to go back to");
        }
        Vm twin = vmRepository.findById(standby.getSpotPartnerVmId())
                .orElseThrow(() -> new ValidationException("The Spot node group is not registered"));
        Job standbyJob = job(standby, userId);
        Job twinJob = job(twin, userId);
        auditService.logEnvironmentAction(userId, AuditAction.SPOT_REVERTED, standbyJob.environmentId(), standbyJob.environmentName(),
                "vm", standby.getVmId(), standby.getDisplayName(), "Going back to on demand from " + twin.getDisplayName());
        later(() -> runRevert(standbyJob, twinJob));
    }

    void runRevert(Job standby, Job twin) {
        CloudProviderService.VmOperationResult start = eks.startVm(standby.cluster() + "/" + standby.nodegroup(), standby.target()).join();
        if (!start.isSuccess()) {
            failed(standby, "The on-demand node group did not start: " + start.getMessage() + ". The Spot node group keeps running.");
            return;
        }
        try {
            eks.stopVm(twin.cluster() + "/" + twin.nodegroup(), twin.target(), false).join();
            eks.deleteNodegroup(twin.target(), twin.cluster(), twin.nodegroup());
        } catch (RuntimeException e) {
            log.warn("Could not remove Spot node group {}/{}: {}", twin.cluster(), twin.nodegroup(), e.getMessage());
        }
        vmRepository.findById(standby.vmId()).ifPresent(v -> {
            v.setStandby(false);
            v.setSpotPartnerVmId(null);
            v.setStatus(VmStatus.RUNNING);
            vmRepository.save(v);
        });
        vmRepository.findById(twin.vmId()).ifPresent(v -> {
            v.setSpotPartnerVmId(null);
            v.setIsActive(false);
            v.setStatus(VmStatus.STOPPED);
            vmRepository.save(v);
        });
        if (notificationService != null) {
            notificationService.notifySpotOutcome(standby.userId(), standby.vmId(), true, "Back on demand: " + standby.vmName(),
                    standby.vmName() + " runs on demand again; the Spot node group " + twin.nodegroup() + " is being deleted.");
        }
    }

    private void failed(Job job, String message) {
        auditService.logEnvironmentFailure(job.userId(), AuditAction.SPOT_CONVERSION_FAILED, job.environmentId(), job.environmentName(),
                "vm", job.vmId(), job.vmName(), message);
        if (notificationService != null) {
            notificationService.notifySpotOutcome(job.userId(), job.vmId(), false, "Spot conversion failed: " + job.vmName(), message);
        }
    }
}
