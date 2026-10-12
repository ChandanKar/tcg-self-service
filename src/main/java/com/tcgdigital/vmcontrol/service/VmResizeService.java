package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.ResizeStatus;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Orchestrates the "Apply" action on a rightsizing recommendation: actually changing a VM's EC2
 * instance type. Only ever called for the recommendation an admin explicitly approved in the UI
 * — never automatic (per the scale-down/scale-up requirements: recommendations are always
 * displayed, never auto-applied).
 *
 * Since E30-T06 a running VM can be resized safely ({@link #resizeWithRestart}, behind
 * {@code rightsizing.safe-resize.enabled}): checks first, then stop, change the type, start and
 * wait for the status checks; if the new type will not start, the old type is put back and the VM
 * started again. Each resize is one {@code vm_resize_event} moving through {@link ResizeStatus}.
 */
@Service
public class VmResizeService {

    private static final Logger log = LoggerFactory.getLogger(VmResizeService.class);

    private final VmRepository vmRepository;
    private final VmInventorySnapshotRepository vmInventorySnapshotRepository;
    private final AwsCloudProviderService awsCloudProviderService;
    private final LockService lockService;
    private final AuditService auditService;
    private final OperationExecutionRepository executionRepository;
    private final CostEstimationService costEstimationService;

    /** Resize history for the savings ledger (E17-T04); optional so unit tests can omit it. */
    private com.tcgdigital.vmcontrol.repository.VmResizeEventRepository resizeEventRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setResizeEventRepository(com.tcgdigital.vmcontrol.repository.VmResizeEventRepository repository) {
        this.resizeEventRepository = repository;
    }

    /** Safe resize (E30-T06): off by default; it stops and starts running VMs. */
    @org.springframework.beans.factory.annotation.Value("${rightsizing.safe-resize.enabled:false}")
    private boolean safeResizeEnabled;

    /** An admin's override from Settings (E29-T03), else the configured value; absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    private NotificationService notificationService;
    /** Runs the workflow after the request commits; directly in unit tests. */
    private com.tcgdigital.vmcontrol.service.support.AfterCommit afterCommit;
    /** Where the workflow runs (the VM operation pool); the caller's thread in unit tests. */
    private java.util.concurrent.Executor workflowExecutor = Runnable::run;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAfterCommit(com.tcgdigital.vmcontrol.service.support.AfterCommit afterCommit) {
        this.afterCommit = afterCommit;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWorkflowExecutor(@org.springframework.beans.factory.annotation.Qualifier("vmOperationExecutor")
                             java.util.concurrent.Executor executor) {
        this.workflowExecutor = executor;
    }

    boolean safeResizeEnabled() {
        return settings == null ? safeResizeEnabled : settings.overrideOr("rightsizing.safe-resize.enabled", safeResizeEnabled);
    }

    public VmResizeService(VmRepository vmRepository,
                            VmInventorySnapshotRepository vmInventorySnapshotRepository,
                            AwsCloudProviderService awsCloudProviderService,
                            LockService lockService,
                            AuditService auditService,
                            OperationExecutionRepository executionRepository,
                            CostEstimationService costEstimationService) {
        this.costEstimationService = costEstimationService;
        this.executionRepository = executionRepository;
        this.vmRepository = vmRepository;
        this.vmInventorySnapshotRepository = vmInventorySnapshotRepository;
        this.awsCloudProviderService = awsCloudProviderService;
        this.lockService = lockService;
        this.auditService = auditService;
    }

    /**
     * Change an AWS VM's instance type (LOW-OPS-RESIZE). Checks the live cloud state (not the
     * database) and that no operation is running in the environment; once AWS has the new type,
     * the request succeeds even if bookkeeping afterwards fails.
     */
    public void applyInstanceTypeChange(String vmId, String targetInstanceType, String userId) {
        Vm vm = vmRepository.findById(vmId)
                .orElseThrow(() -> new ResourceNotFoundException("VM", vmId));

        if (vm.getProvider() != CloudProvider.AWS) {
            throw new ValidationException("Instance type changes are only supported for AWS VMs");
        }

        String environmentId = vm.getGroup().getEnvironment().getEnvironmentId();
        String environmentName = vm.getGroup().getEnvironment().getDisplayName();
        lockService.verifyLockPermission(environmentId, userId);

        if (executionRepository.hasActiveOperations(environmentId)) {
            throw new ValidationException("An operation is running in this environment");
        }

        // Trust AWS, not the database: correct the stored status when they disagree.
        VmStatus liveStatus = awsCloudProviderService.getVmStatus(vm.getProviderVmId(), CloudTarget.of(vm));
        if (liveStatus != null && liveStatus != VmStatus.UNKNOWN && liveStatus != vm.getStatus()) {
            vmRepository.updateStatusIfCurrent(vmId, vm.getStatus(), liveStatus, Timestamp.from(Instant.now()));
        }
        if (liveStatus != VmStatus.STOPPED) {
            throw new ValidationException("VM must be stopped before changing its instance type (it is "
                    + (liveStatus != null ? liveStatus : VmStatus.UNKNOWN) + ")");
        }

        // Only a type the page recommends right now (M11, E08-T05): the suggestion or an applicable
        // alternative of the same architecture (E30-T05). A replayed or edited request cannot
        // resize the VM to anything else.
        boolean matches = costEstimationService.findCandidate(vmId)
                .map(c -> c.canApply(targetInstanceType))
                .orElse(false);
        if (!matches) {
            throw new ValidationException("Target instance type does not match the current recommendation for this VM");
        }

        String oldInstanceType = vmInventorySnapshotRepository.findByVmVmId(vmId)
                .map(VmInventorySnapshot::getInstanceType)
                .orElse(null);

        auditService.logEnvironmentAction(userId, AuditAction.VM_RESIZE_REQUESTED, environmentId, environmentName,
                "vm", vmId, vm.getDisplayName(),
                "Requested change from " + oldInstanceType + " to " + targetInstanceType);

        try {
            awsCloudProviderService.changeInstanceType(CloudTarget.of(vm), vm.getProviderVmId(), targetInstanceType);
        } catch (RuntimeException e) {
            // The call can fail after AWS applied it (timeout, throttled retry): check before failing.
            String liveType = liveInstanceType(vm);
            if (!targetInstanceType.equals(liveType)) {
                auditService.logEnvironmentFailure(userId, AuditAction.VM_RESIZE_FAILED, environmentId, environmentName,
                        "vm", vmId, vm.getDisplayName(), e.getMessage());
                throw e;
            }
            log.warn("Instance type change for VM {} reported an error but AWS shows {}: treating as done ({})",
                    vmId, liveType, e.getMessage());
        }

        // The type changed: bookkeeping problems from here on are logged, never reported as failure.
        try {
            VmInventorySnapshot snapshot = vmInventorySnapshotRepository.findByVmVmId(vmId).orElseGet(() -> {
                VmInventorySnapshot created = new VmInventorySnapshot();
                created.setVm(vm);
                created.setProvider(vm.getProvider());
                created.setProviderVmId(vm.getProviderVmId());
                return created;
            });
            snapshot.setInstanceType(targetInstanceType);
            snapshot.setInstanceTypeChangedAt(Timestamp.from(Instant.now())); // E08-T06
            snapshot.setLastRefreshedAt(Timestamp.from(Instant.now()));
            vmInventorySnapshotRepository.save(snapshot);
        } catch (Exception e) {
            log.warn("Instance type of VM {} changed to {} but the inventory snapshot was not updated: {}",
                    vmId, targetInstanceType, e.getMessage());
        }
        if (resizeEventRepository != null && oldInstanceType != null) {
            try {
                resizeEventRepository.save(new com.tcgdigital.vmcontrol.model.VmResizeEvent(
                        java.util.UUID.randomUUID().toString(), vmId, oldInstanceType, targetInstanceType,
                        com.tcgdigital.vmcontrol.model.VmResizeEvent.Source.APPLY, Timestamp.from(Instant.now()), userId));
            } catch (Exception e) {
                log.warn("Resize of VM {} was not recorded for the savings ledger: {}", vmId, e.getMessage());
            }
        }
        auditService.logChange(userId, AuditAction.VM_RESIZE_COMPLETED, "vm", vmId, vm.getDisplayName(),
                oldInstanceType, targetInstanceType, null);
        costEstimationService.invalidateBundles(); // the page must not offer the old recommendation
    }

    // ---------------------------------------------------------------- safe resize (E30-T06)

    /** One pre-check of a safe resize: its name, whether it passed and what it found (E30-T06). */
    public record ResizeCheck(String name, boolean passed, String message) {
        static ResizeCheck pass(String name, String message) {
            return new ResizeCheck(name, true, message);
        }

        static ResizeCheck fail(String name, String message) {
            return new ResizeCheck(name, false, message);
        }
    }

    /** What the workflow needs, captured in the request so it never touches lazy entities later. */
    private record Workflow(String eventId, String vmId, String vmName, String providerVmId, CloudTarget target,
                            String environmentId, String environmentName, String userId, String oldType,
                            String newType, boolean wasRunning) {
    }

    /**
     * Every check a safe resize of {@code vmId} to {@code targetInstanceType} makes, without changing
     * anything (E30-T06): the switch, the caller's lock, no operation or resize running, the type
     * recommended, a running or stopped VM, the type offered in the VM's availability zone, the
     * instance's architecture and ENA support.
     */
    public List<ResizeCheck> precheck(String vmId, String targetInstanceType, String userId) {
        Vm vm = vmRepository.findById(vmId).orElseThrow(() -> new ResourceNotFoundException("VM", vmId));
        if (vm.getProvider() != CloudProvider.AWS) {
            throw new ValidationException("Instance type changes are only supported for AWS VMs");
        }
        return precheck(vm, targetInstanceType, userId, liveStatus(vm));
    }

    private List<ResizeCheck> precheck(Vm vm, String targetInstanceType, String userId, VmStatus liveStatus) {
        List<ResizeCheck> checks = new java.util.ArrayList<>();
        String environmentId = vm.getGroup().getEnvironment().getEnvironmentId();
        checks.add(safeResizeEnabled() ? ResizeCheck.pass("Safe resize", "Turned on")
                : ResizeCheck.fail("Safe resize", "Safe resize is turned off (rightsizing.safe-resize.enabled)"));
        try {
            lockService.verifyLockPermission(environmentId, userId);
            checks.add(ResizeCheck.pass("Environment lock", "You hold the environment's lock"));
        } catch (RuntimeException e) {
            checks.add(ResizeCheck.fail("Environment lock", e.getMessage()));
        }
        boolean busy = executionRepository.hasActiveOperations(environmentId);
        boolean resizing = resizeEventRepository != null && resizeEventRepository.existsInProgress(vm.getVmId());
        checks.add(busy ? ResizeCheck.fail("No operation running", "An operation is running in this environment")
                : resizing ? ResizeCheck.fail("No operation running", "A resize of this VM is already in progress")
                : ResizeCheck.pass("No operation running", "Nothing else is changing this environment"));
        boolean recommended = costEstimationService.findCandidate(vm.getVmId())
                .map(c -> c.canApply(targetInstanceType)).orElse(false);
        checks.add(recommended ? ResizeCheck.pass("Recommended type", targetInstanceType + " is recommended for this VM")
                : ResizeCheck.fail("Recommended type", "Target instance type does not match the current recommendation for this VM"));
        checks.add(liveStatus == VmStatus.RUNNING
                ? ResizeCheck.pass("VM state", "Running: it will be stopped, resized and started again")
                : liveStatus == VmStatus.STOPPED ? ResizeCheck.pass("VM state", "Stopped: it will be resized and stay stopped")
                : ResizeCheck.fail("VM state", "The VM must be running or stopped (it is "
                        + (liveStatus != null ? liveStatus : VmStatus.UNKNOWN) + ")"));
        CloudTarget target = CloudTarget.of(vm);
        try {
            AwsCloudProviderService.ResizeFacts facts = awsCloudProviderService.describeForResize(target, vm.getProviderVmId());
            if (facts == null) {
                checks.add(ResizeCheck.fail("Instance found", "AWS does not know instance " + vm.getProviderVmId()));
                return checks;
            }
            String az = facts.availabilityZone();
            checks.add(az != null && awsCloudProviderService.isInstanceTypeOffered(target, targetInstanceType, az)
                    ? ResizeCheck.pass("Offered in the zone", targetInstanceType + " is offered in " + az)
                    : ResizeCheck.fail("Offered in the zone", targetInstanceType + " is not offered in " + (az == null ? "the VM's zone" : az)));
            AwsCloudProviderService.InstanceTypeFacts type = awsCloudProviderService.describeInstanceType(target, targetInstanceType);
            if (type == null) {
                checks.add(ResizeCheck.fail("Architecture", "AWS does not know instance type " + targetInstanceType));
            } else {
                String arch = facts.architecture();
                checks.add(arch == null || type.supportedArchitectures().contains(arch)
                        ? ResizeCheck.pass("Architecture", targetInstanceType + " runs " + (arch == null ? "the VM's" : arch) + " images")
                        : ResizeCheck.fail("Architecture", targetInstanceType + " does not run " + arch + " images"));
                checks.add(!type.requiresEna() || Boolean.TRUE.equals(facts.enaSupport())
                        ? ResizeCheck.pass("Networking (ENA)", type.requiresEna() ? "ENA is enabled on the instance" : "No ENA requirement")
                        : ResizeCheck.fail("Networking (ENA)", targetInstanceType + " needs ENA, which is not enabled on the instance"));
            }
        } catch (RuntimeException e) {
            checks.add(ResizeCheck.fail("AWS checks", "Could not check the instance in AWS: " + e.getMessage()));
        }
        return checks;
    }

    /**
     * Safe resize (E30-T06): every pre-check must pass (else 400 and nothing changes in AWS); then a
     * REQUESTED resize is recorded and the stop-resize-start workflow runs in the background after
     * the request commits. Returns the resize id to follow with {@link #getResize}.
     */
    public String resizeWithRestart(String vmId, String targetInstanceType, String userId) {
        Vm vm = vmRepository.findById(vmId).orElseThrow(() -> new ResourceNotFoundException("VM", vmId));
        if (vm.getProvider() != CloudProvider.AWS) {
            throw new ValidationException("Instance type changes are only supported for AWS VMs");
        }
        if (resizeEventRepository == null) {
            throw new IllegalStateException("Resize history is not available");
        }
        VmStatus live = liveStatus(vm);
        List<ResizeCheck> failed = precheck(vm, targetInstanceType, userId, live).stream().filter(c -> !c.passed()).toList();
        if (!failed.isEmpty()) {
            throw new ValidationException(failed.stream().map(ResizeCheck::message).collect(java.util.stream.Collectors.joining("; ")));
        }
        String environmentId = vm.getGroup().getEnvironment().getEnvironmentId();
        String environmentName = vm.getGroup().getEnvironment().getDisplayName();
        String oldType = vmInventorySnapshotRepository.findByVmVmId(vmId).map(VmInventorySnapshot::getInstanceType)
                .orElseGet(() -> liveInstanceType(vm));
        if (oldType == null) {
            throw new ValidationException("The VM's current instance type is not known yet; refresh its inventory first");
        }
        Timestamp now = Timestamp.from(Instant.now());
        com.tcgdigital.vmcontrol.model.VmResizeEvent event = new com.tcgdigital.vmcontrol.model.VmResizeEvent(
                java.util.UUID.randomUUID().toString(), vmId, oldType, targetInstanceType,
                com.tcgdigital.vmcontrol.model.VmResizeEvent.Source.APPLY, now, userId);
        event.setStatus(ResizeStatus.REQUESTED);
        event.setStage("Requested");
        event.setRestartRequested(true);
        event.setOriginalStatus(live.name());
        event.setUpdatedAt(now);
        resizeEventRepository.save(event);
        auditService.logEnvironmentAction(userId, AuditAction.VM_RESIZE_REQUESTED, environmentId, environmentName,
                "vm", vmId, vm.getDisplayName(), "Requested safe change from " + oldType + " to " + targetInstanceType
                        + (live == VmStatus.RUNNING ? " (stop, resize, start)" : ""));

        Workflow workflow = new Workflow(event.getEventId(), vmId, vm.getDisplayName(), vm.getProviderVmId(), CloudTarget.of(vm),
                environmentId, environmentName, userId, oldType, targetInstanceType, live == VmStatus.RUNNING);
        Runnable launch = () -> workflowExecutor.execute(() -> runWorkflow(workflow));
        if (afterCommit != null) {
            afterCommit.run(launch);
        } else {
            launch.run();
        }
        return event.getEventId();
    }

    /** A resize's current state (E30-T06). */
    public com.tcgdigital.vmcontrol.model.VmResizeEvent getResize(String resizeId) {
        if (resizeEventRepository == null) {
            throw new ResourceNotFoundException("Resize", resizeId);
        }
        return resizeEventRepository.findById(resizeId).orElseThrow(() -> new ResourceNotFoundException("Resize", resizeId));
    }

    private void runWorkflow(Workflow w) {
        try {
            if (w.wasRunning()) {
                stage(w, ResizeStatus.STOPPING, "Stopping the VM", null);
                CloudProviderService.VmOperationResult stop = awsCloudProviderService.stopVm(w.providerVmId(), w.target(), false).join();
                if (!stop.isSuccess() || awsCloudProviderService.getVmStatus(w.providerVmId(), w.target()) != VmStatus.STOPPED) {
                    recordStatus(w.vmId(), awsCloudProviderService.getVmStatus(w.providerVmId(), w.target()));
                    fail(w, "The VM did not stop: " + stop.getMessage() + ". Nothing was changed.", false);
                    return;
                }
                recordStatus(w.vmId(), VmStatus.STOPPED);
            }

            stage(w, ResizeStatus.RESIZING, "Changing the instance type to " + w.newType(), null);
            try {
                awsCloudProviderService.changeInstanceType(w.target(), w.providerVmId(), w.newType());
            } catch (RuntimeException e) {
                if (!w.newType().equals(liveInstanceType(w.target().region(), w.providerVmId()))) {
                    String restarted = w.wasRunning() ? restartOriginal(w) : "";
                    fail(w, "The instance type could not be changed: " + e.getMessage() + restarted, false);
                    return;
                }
                log.warn("Instance type change for VM {} reported an error but AWS shows {}: treating as done ({})",
                        w.vmId(), w.newType(), e.getMessage());
            }
            recordInstanceType(w.vmId(), w.newType());

            if (w.wasRunning()) {
                stage(w, ResizeStatus.STARTING, "Starting on " + w.newType() + " and waiting for the status checks", null);
                CloudProviderService.VmOperationResult start = startQuietly(w);
                if (!start.isSuccess()) {
                    rollBack(w, "The VM did not start on " + w.newType() + ": " + start.getMessage());
                    return;
                }
                recordStatus(w.vmId(), VmStatus.RUNNING);
            }
            complete(w);
        } catch (RuntimeException e) {
            log.error("Safe resize {} of VM {} failed unexpectedly", w.eventId(), w.vmId(), e);
            fail(w, "Unexpected error: " + e.getMessage() + ". Check the VM in AWS.", true);
        }
    }

    private CloudProviderService.VmOperationResult startQuietly(Workflow w) {
        try {
            return awsCloudProviderService.startVm(w.providerVmId(), w.target()).join();
        } catch (RuntimeException e) {
            Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
            return CloudProviderService.VmOperationResult.failure(cause.getMessage());
        }
    }

    /** The new type would not start: back to the old one, start again (E30-T06). */
    private void rollBack(Workflow w, String reason) {
        stage(w, ResizeStatus.ROLLING_BACK, "Going back to " + w.oldType(), reason);
        try {
            if (awsCloudProviderService.getVmStatus(w.providerVmId(), w.target()) != VmStatus.STOPPED) {
                CloudProviderService.VmOperationResult stop = awsCloudProviderService.stopVm(w.providerVmId(), w.target(), true).join();
                if (!stop.isSuccess()) {
                    throw new IllegalStateException("it could not be stopped: " + stop.getMessage());
                }
            }
            awsCloudProviderService.changeInstanceType(w.target(), w.providerVmId(), w.oldType());
            recordInstanceType(w.vmId(), w.oldType());
            CloudProviderService.VmOperationResult start = startQuietly(w);
            if (!start.isSuccess()) {
                throw new IllegalStateException("it did not start again on " + w.oldType() + ": " + start.getMessage());
            }
            recordStatus(w.vmId(), VmStatus.RUNNING);
        } catch (RuntimeException e) {
            recordStatus(w.vmId(), safeStatus(w));
            fail(w, reason + ". Going back to " + w.oldType() + " failed too: " + e.getMessage()
                    + ". Check the VM's instance type and start it in AWS.", true);
            return;
        }
        finish(w, ResizeStatus.ROLLED_BACK, "Back on " + w.oldType() + " and running", reason);
        auditService.logEnvironmentFailure(w.userId(), AuditAction.VM_RESIZE_ROLLED_BACK, w.environmentId(), w.environmentName(),
                "vm", w.vmId(), w.vmName(), reason + "; reverted to " + w.oldType());
        if (notificationService != null) {
            notificationService.notifyResizeOutcome(w.userId(), w.vmId(), false, "Resize rolled back: " + w.vmName(),
                    w.vmName() + " did not start on " + w.newType() + ", so it is back on " + w.oldType() + " and running. " + reason);
        }
        costEstimationService.invalidateBundles();
    }

    /** After a failed type change, start the VM again on its old type; returns a note for the error. */
    private String restartOriginal(Workflow w) {
        CloudProviderService.VmOperationResult start = startQuietly(w);
        recordStatus(w.vmId(), start.isSuccess() ? VmStatus.RUNNING : safeStatus(w));
        return start.isSuccess() ? ". The VM was started again on " + w.oldType() + "."
                : ". Starting it again on " + w.oldType() + " failed: " + start.getMessage();
    }

    private void complete(Workflow w) {
        finish(w, ResizeStatus.COMPLETED, w.wasRunning() ? "Running on " + w.newType() : "Changed to " + w.newType(), null);
        auditService.logChange(w.userId(), AuditAction.VM_RESIZE_COMPLETED, "vm", w.vmId(), w.vmName(),
                w.oldType(), w.newType(), null);
        if (notificationService != null) {
            notificationService.notifyResizeOutcome(w.userId(), w.vmId(), true, "Resize completed: " + w.vmName(),
                    w.vmName() + " now runs on " + w.newType() + " (was " + w.oldType() + ").");
        }
        costEstimationService.invalidateBundles();
    }

    private void fail(Workflow w, String error, boolean needsAttention) {
        finish(w, ResizeStatus.FAILED, "Failed", error);
        auditService.logEnvironmentFailure(w.userId(), AuditAction.VM_RESIZE_FAILED, w.environmentId(), w.environmentName(),
                "vm", w.vmId(), w.vmName(), error);
        if (notificationService != null) {
            notificationService.notifyResizeOutcome(w.userId(), w.vmId(), false, "Resize failed: " + w.vmName(), error);
            if (needsAttention) {
                notificationService.notifyResizeNeedsAttention(w.environmentId(), w.vmId(), "VM needs attention: " + w.vmName(),
                        "A resize of " + w.vmName() + " from " + w.oldType() + " to " + w.newType() + " failed and could not be undone. "
                                + error);
            }
        }
        costEstimationService.invalidateBundles();
    }

    private void stage(Workflow w, ResizeStatus status, String stage, String error) {
        update(w, status, stage, error, false);
    }

    private void finish(Workflow w, ResizeStatus status, String stage, String error) {
        update(w, status, stage, error, true);
    }

    private void update(Workflow w, ResizeStatus status, String stage, String error, boolean done) {
        try {
            resizeEventRepository.findById(w.eventId()).ifPresent(e -> {
                Timestamp now = Timestamp.from(Instant.now());
                e.setStatus(status);
                e.setStage(stage);
                if (error != null) {
                    e.setErrorMessage(error);
                }
                if (done && status == ResizeStatus.COMPLETED) {
                    e.setChangedAt(now); // the ledger counts the change from when it happened
                }
                e.setUpdatedAt(now);
                resizeEventRepository.save(e);
            });
        } catch (RuntimeException e) {
            log.warn("Could not record resize {} as {}: {}", w.eventId(), status, e.getMessage());
        }
    }

    private VmStatus safeStatus(Workflow w) {
        try {
            VmStatus s = awsCloudProviderService.getVmStatus(w.providerVmId(), w.target());
            return s == null ? VmStatus.UNKNOWN : s;
        } catch (RuntimeException e) {
            return VmStatus.UNKNOWN;
        }
    }

    private void recordStatus(String vmId, VmStatus status) {
        if (status == null) {
            return;
        }
        try {
            vmRepository.findById(vmId).ifPresent(v -> {
                if (v.getStatus() != status) {
                    vmRepository.updateStatusIfCurrent(vmId, v.getStatus(), status, Timestamp.from(Instant.now()));
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not record VM {} as {}: {}", vmId, status, e.getMessage());
        }
    }

    private void recordInstanceType(String vmId, String type) {
        try {
            vmInventorySnapshotRepository.findByVmVmId(vmId).ifPresent(snapshot -> {
                snapshot.setInstanceType(type);
                snapshot.setInstanceTypeChangedAt(Timestamp.from(Instant.now())); // E08-T06
                snapshot.setLastRefreshedAt(Timestamp.from(Instant.now()));
                vmInventorySnapshotRepository.save(snapshot);
            });
        } catch (RuntimeException e) {
            log.warn("Instance type of VM {} is {} but the inventory snapshot was not updated: {}", vmId, type, e.getMessage());
        }
    }

    /** Live status from AWS, correcting the stored one when they disagree. */
    private VmStatus liveStatus(Vm vm) {
        VmStatus live = awsCloudProviderService.getVmStatus(vm.getProviderVmId(), CloudTarget.of(vm));
        if (live != null && live != VmStatus.UNKNOWN && live != vm.getStatus()) {
            vmRepository.updateStatusIfCurrent(vm.getVmId(), vm.getStatus(), live, Timestamp.from(Instant.now()));
        }
        return live == null ? VmStatus.UNKNOWN : live;
    }

    private String liveInstanceType(String region, String providerVmId) {
        try {
            return awsCloudProviderService.getInstanceType(region, providerVmId);
        } catch (Exception e) {
            log.warn("Could not read the live instance type of {}: {}", providerVmId, e.getMessage());
            return null;
        }
    }

    private String liveInstanceType(Vm vm) {
        try {
            return awsCloudProviderService.getInstanceType(vm.getRegion(), vm.getProviderVmId());
        } catch (Exception e) {
            log.warn("Could not read the live instance type of VM {}: {}", vm.getVmId(), e.getMessage());
            return null;
        }
    }
}
