package com.tcgdigital.vmcontrol.service.provisioning;

import com.tcgdigital.vmcontrol.exception.ConflictException;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.EnvironmentTemplate;
import com.tcgdigital.vmcontrol.model.ProvisioningRequest;
import com.tcgdigital.vmcontrol.model.ProvisioningStatus;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.ProvisioningRequestRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import com.tcgdigital.vmcontrol.service.NotificationService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.LeaseExtendedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The end of a provisioned environment (E32-T05): warnings a day and an hour before its destroy
 * time, extension by its requester or administrators (capped at the template's maximum lifetime
 * unless an admin extends it; extending the environment's lease extends it too), and destruction:
 * the engine destroys what it built, then the environment is deactivated and the requester's
 * access ends. The automatic destroy pass runs only with {@code provisioning.destroy.enabled};
 * a manual destroy needs an explicit confirmation.
 */
@Service
public class ProvisioningDestroyService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningDestroyService.class);

    private final ProvisioningRequestRepository repository;
    private final TemplateService templateService;
    private final ProvisioningEngineRegistry engines;
    private final EnvironmentService environmentService;
    private final EnvironmentAccessService accessService;
    private final OperationExecutionRepository executionRepository;
    private final SecurityService securityService;
    private final AuditService auditService;
    private NotificationService notificationService;
    private Clock clock = Clock.systemUTC();

    @Value("${provisioning.destroy.enabled:false}")
    private boolean destroyEnabled;

    /** An admin's override from Settings (E29-T03), else the configured value; absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    public ProvisioningDestroyService(ProvisioningRequestRepository repository, TemplateService templateService,
                                      ProvisioningEngineRegistry engines, EnvironmentService environmentService,
                                      EnvironmentAccessService accessService, OperationExecutionRepository executionRepository,
                                      SecurityService securityService, AuditService auditService) {
        this.repository = repository;
        this.templateService = templateService;
        this.engines = engines;
        this.environmentService = environmentService;
        this.accessService = accessService;
        this.executionRepository = executionRepository;
        this.securityService = securityService;
        this.auditService = auditService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public boolean destroyEnabled() {
        return settings == null ? destroyEnabled : settings.overrideOr("provisioning.destroy.enabled", destroyEnabled);
    }

    /** Warns each owner once a day before and once an hour before the destroy time. Returns warnings sent. */
    public int warn() {
        if (!templateService.isEnabled()) {
            return 0;
        }
        Instant now = clock.instant();
        int sent = 0;
        for (ProvisioningRequest r : repository.findByStatusOrderByCreatedAtAsc(ProvisioningStatus.READY)) {
            if (r.getDestroyAt() == null) {
                continue;
            }
            Duration left = Duration.between(now, r.getDestroyAt().toInstant());
            boolean hour = left.compareTo(Duration.ofHours(1)) <= 0 && !r.isWarnedHour();
            boolean day = left.compareTo(Duration.ofHours(24)) <= 0 && !r.isWarnedDay();
            if (!hour && !day) {
                continue;
            }
            r.setWarnedDay(true);
            if (hour) {
                r.setWarnedHour(true);
            }
            repository.save(r);
            if (notificationService != null) {
                notificationService.notifyEnvironmentDestroyWarning(r.getRequestedByUserId(), r.getEnvironmentId(),
                        r.getEnvironmentName(), r.getDestroyAt().toInstant(), hour ? "1 hour" : "24 hours");
            }
            sent++;
        }
        return sent;
    }

    /**
     * Pushes the destroy time back by {@code hours}. The requester or the environment's
     * administrators may do it up to the template's maximum lifetime; an admin may go beyond it.
     */
    public ProvisioningRequest extendTtl(String requestId, int hours, String userId) {
        templateService.requireEnabled();
        ProvisioningRequest r = find(requestId);
        if (!userId.equals(r.getRequestedByUserId()) && !securityService.isAdmin()
                && (r.getEnvironmentId() == null || !securityService.canAdministerEnvironment(r.getEnvironmentId()))) {
            throw new ResourceNotFoundException("Provisioning request", requestId);
        }
        if (r.getStatus() != ProvisioningStatus.READY || r.getDestroyAt() == null) {
            throw new ConflictException("Only a ready environment can be extended");
        }
        if (hours < 1) {
            throw new ValidationException("Extend by at least 1 hour");
        }
        Instant newEnd = r.getDestroyAt().toInstant().plus(Duration.ofHours(hours));
        return moveDestroyAt(r, newEnd, userId, !securityService.isAdmin());
    }

    /** Extending the environment's lease extends its destroy time too, within the template's maximum (E32-T05). */
    @EventListener
    public void onLeaseExtended(LeaseExtendedEvent event) {
        if (!templateService.isEnabled()) {
            return;
        }
        repository.findByEnvironmentIdAndStatus(event.environmentId(), ProvisioningStatus.READY).ifPresent(r -> {
            if (r.getDestroyAt() != null && event.newEnd().isAfter(r.getDestroyAt().toInstant())) {
                try {
                    moveDestroyAt(r, event.newEnd(), event.userId(), true);
                } catch (ValidationException e) {
                    // the lease may run longer than the template allows; the destroy time stays at the maximum
                    EnvironmentTemplate template = templateService.find(r.getTemplateId());
                    Instant cap = r.getCreatedAt().toInstant().plus(Duration.ofHours(template.getMaxTtlHours()));
                    if (cap.isAfter(r.getDestroyAt().toInstant())) {
                        moveDestroyAt(r, cap, event.userId(), true);
                    }
                }
            }
        });
    }

    private ProvisioningRequest moveDestroyAt(ProvisioningRequest r, Instant newEnd, String userId, boolean capped) {
        EnvironmentTemplate template = templateService.find(r.getTemplateId());
        Instant cap = r.getCreatedAt().toInstant().plus(Duration.ofHours(template.getMaxTtlHours()));
        if (capped && newEnd.isAfter(cap)) {
            throw new ValidationException("This template allows at most " + template.getMaxTtlHours()
                    + " hours from the request; it can last until " + cap);
        }
        Instant old = r.getDestroyAt().toInstant();
        r.setDestroyAt(Timestamp.from(newEnd));
        Duration left = Duration.between(clock.instant(), newEnd);
        r.setWarnedDay(left.compareTo(Duration.ofHours(24)) <= 0 && r.isWarnedDay());
        r.setWarnedHour(left.compareTo(Duration.ofHours(1)) <= 0 && r.isWarnedHour());
        ProvisioningRequest saved = repository.save(r);
        auditService.logAction(userId, AuditAction.PROVISIONING_TTL_EXTENDED, "provisioning_request", r.getRequestId(),
                r.getEnvironmentName(), "Destroy time " + old + " -> " + newEnd);
        return saved;
    }

    /** Destroys ready environments past their destroy time (only with provisioning.destroy.enabled). */
    public int destroyDue() {
        if (!templateService.isEnabled() || !destroyEnabled()) {
            return 0;
        }
        Timestamp now = Timestamp.from(clock.instant());
        int started = 0;
        for (ProvisioningRequest r : repository.findByDestroyAtBeforeAndStatus(now, ProvisioningStatus.READY)) {
            if (r.getEnvironmentId() != null && executionRepository.hasActiveOperations(r.getEnvironmentId())) {
                continue; // let the running start or stop finish first
            }
            started += startDestroy(r, "Lifetime ended") ? 1 : 0;
        }
        return started;
    }

    /** A manual destroy by the requester or an admin, confirmed. */
    public ProvisioningRequest destroyNow(String requestId, String userId, boolean confirm) {
        templateService.requireEnabled();
        ProvisioningRequest r = find(requestId);
        if (!userId.equals(r.getRequestedByUserId()) && !securityService.isAdmin()) {
            throw new UnauthorizedException("Only the requester or an admin can destroy this environment");
        }
        if (!confirm) {
            throw new ValidationException("Confirm the destroy (confirm=true): everything in the environment is deleted");
        }
        if (r.getStatus() != ProvisioningStatus.READY) {
            throw new ConflictException("Only a ready environment can be destroyed");
        }
        if (r.getEnvironmentId() != null && executionRepository.hasActiveOperations(r.getEnvironmentId())) {
            throw new ConflictException("An operation is running in this environment; try again when it ends");
        }
        auditService.logAction(userId, AuditAction.PROVISIONING_DESTROY_REQUESTED, "provisioning_request", requestId,
                r.getEnvironmentName(), "Destroy requested");
        startDestroy(r, "Destroyed on request");
        return find(requestId);
    }

    private boolean startDestroy(ProvisioningRequest r, String why) {
        Timestamp now = Timestamp.from(clock.instant());
        if (repository.moveIfStatus(r.getRequestId(), ProvisioningStatus.READY, ProvisioningStatus.DESTROYING, now) == 0) {
            return false;
        }
        EnvironmentTemplate template = templateService.find(r.getTemplateId());
        ProvisioningRequest fresh = find(r.getRequestId());
        try {
            fresh.setDestroyRecordId(engines.get(template.getEngine()).destroy(fresh, template));
            fresh.setErrorMessage(null);
        } catch (RuntimeException e) {
            fresh.setErrorMessage("Destroy could not start: " + e.getMessage());
        }
        repository.save(fresh);
        auditService.logAction(fresh.getRequestedByUserId(), AuditAction.PROVISIONING_DESTROY_REQUESTED, "provisioning_request",
                fresh.getRequestId(), fresh.getEnvironmentName(), why);
        return true;
    }

    /** Follows destroys; on success the environment is deactivated and the requester's access ends. */
    public int pollDestroying() {
        if (!templateService.isEnabled()) {
            return 0;
        }
        int done = 0;
        for (ProvisioningRequest r : repository.findByStatusOrderByCreatedAtAsc(ProvisioningStatus.DESTROYING)) {
            EnvironmentTemplate template = templateService.find(r.getTemplateId());
            if (r.getDestroyRecordId() == null) {
                continue; // a destroy that could not start: an admin retries it
            }
            ProvisioningProgress progress;
            try {
                progress = engines.get(template.getEngine()).poll(template, r.getDestroyRecordId());
            } catch (RuntimeException e) {
                log.warn("Destroy of {} could not be checked: {}", r.getRequestId(), e.getMessage());
                continue;
            }
            if (progress.state() == ProvisioningProgress.State.SUCCEEDED) {
                finishDestroy(r);
                done++;
            } else if (progress.state() == ProvisioningProgress.State.FAILED && !progress.message().equals(r.getErrorMessage())) {
                r.setErrorMessage(progress.message());
                repository.save(r);
                if (notificationService != null) {
                    notificationService.notifyEnvironmentDestroyed(r.getRequestedByUserId(), r.getEnvironmentId(),
                            r.getEnvironmentName(), false, progress.message());
                }
            }
        }
        return done;
    }

    private void finishDestroy(ProvisioningRequest r) {
        if (r.getEnvironmentId() != null) {
            try {
                environmentService.deactivateEnvironment(r.getEnvironmentId(), r.getRequestedByUserId());
            } catch (RuntimeException e) {
                log.warn("Destroyed environment {} could not be deactivated: {}", r.getEnvironmentName(), e.getMessage());
            }
            try {
                accessService.revokeAccess(r.getEnvironmentId(), r.getRequestedByUserId(), r.getRequestedByUserId());
            } catch (RuntimeException e) {
                log.debug("No grant to revoke on {}: {}", r.getEnvironmentName(), e.getMessage());
            }
        }
        r.setStatus(ProvisioningStatus.DESTROYED);
        r.setErrorMessage(null);
        repository.save(r);
        auditService.logAction(r.getRequestedByUserId(), AuditAction.PROVISIONING_DESTROYED, "provisioning_request",
                r.getRequestId(), r.getEnvironmentName(), "Environment destroyed and deactivated");
        if (notificationService != null) {
            notificationService.notifyEnvironmentDestroyed(r.getRequestedByUserId(), r.getEnvironmentId(),
                    r.getEnvironmentName(), true, null);
        }
    }

    private ProvisioningRequest find(String requestId) {
        return repository.findById(requestId).orElseThrow(() -> new ResourceNotFoundException("Provisioning request", requestId));
    }

    List<ProvisioningRequest> ready() {
        return repository.findByStatusOrderByCreatedAtAsc(ProvisioningStatus.READY);
    }
}
