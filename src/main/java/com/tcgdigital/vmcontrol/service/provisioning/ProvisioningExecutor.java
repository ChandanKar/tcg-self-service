package com.tcgdigital.vmcontrol.service.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcgdigital.vmcontrol.dto.CreateEnvironmentDTO;
import com.tcgdigital.vmcontrol.dto.GrantAccessDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentTemplate;
import com.tcgdigital.vmcontrol.model.ProvisioningRequest;
import com.tcgdigital.vmcontrol.model.ProvisioningStatus;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.ProvisioningRequestRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import com.tcgdigital.vmcontrol.service.NotificationService;
import com.tcgdigital.vmcontrol.service.VmDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Builds approved requests (E32-T04). Each pass starts the APPROVED ones (moved to PROVISIONING
 * first, conditionally, so two servers never start the same one; the engine's idempotency token
 * guards AWS too) and polls the PROVISIONING ones. On success the environment is created under
 * the requested name, its tagged instances are registered, the requester gets ADMIN access until
 * the environment's destroy time, and is told. A failure or a run longer than
 * {@code provisioning.timeout-minutes} ends FAILED with the reason; nothing is created then.
 */
@Service
public class ProvisioningExecutor {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningExecutor.class);

    /** Platform events for webhooks (E34-T07); absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.events.PlatformEvents platformEvents;

    private final ProvisioningRequestRepository repository;
    private final TemplateService templateService;
    private final ProvisioningEngineRegistry engines;
    private final EnvironmentService environmentService;
    private final EnvironmentAccessService accessService;
    private final VmDiscoveryService discoveryService;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionOperations tx;
    private NotificationService notificationService;
    private Clock clock = Clock.systemUTC();

    @Value("${provisioning.timeout-minutes:60}")
    private int timeoutMinutes = 60;

    /** An admin's override from Settings (E29-T03), else the configured value; absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    private int timeout() {
        return settings == null ? timeoutMinutes : settings.overrideOr("provisioning.timeout-minutes", timeoutMinutes);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ProvisioningExecutor(ProvisioningRequestRepository repository, TemplateService templateService,
                                ProvisioningEngineRegistry engines, EnvironmentService environmentService,
                                EnvironmentAccessService accessService, VmDiscoveryService discoveryService,
                                UserRepository userRepository, AuditService auditService, ObjectMapper objectMapper,
                                PlatformTransactionManager transactionManager) {
        this(repository, templateService, engines, environmentService, accessService, discoveryService, userRepository,
                auditService, objectMapper, new TransactionTemplate(transactionManager));
    }

    /** Test seam: no transactions. */
    ProvisioningExecutor(ProvisioningRequestRepository repository, TemplateService templateService,
                         ProvisioningEngineRegistry engines, EnvironmentService environmentService,
                         EnvironmentAccessService accessService, VmDiscoveryService discoveryService,
                         UserRepository userRepository, AuditService auditService, ObjectMapper objectMapper,
                         TransactionOperations tx) {
        this.repository = repository;
        this.templateService = templateService;
        this.engines = engines;
        this.environmentService = environmentService;
        this.accessService = accessService;
        this.discoveryService = discoveryService;
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.tx = tx;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** One pass: start approved requests, then poll the running ones. Returns how many moved on. */
    public int run() {
        if (!templateService.isEnabled()) {
            return 0;
        }
        int moved = 0;
        for (ProvisioningRequest r : repository.findByStatusOrderByCreatedAtAsc(ProvisioningStatus.APPROVED)) {
            moved += start(r) ? 1 : 0;
        }
        for (ProvisioningRequest r : repository.findByStatusOrderByCreatedAtAsc(ProvisioningStatus.PROVISIONING)) {
            moved += poll(r) ? 1 : 0;
        }
        return moved;
    }

    private boolean start(ProvisioningRequest r) {
        Timestamp now = Timestamp.from(clock.instant());
        if (repository.moveIfStatus(r.getRequestId(), ProvisioningStatus.APPROVED, ProvisioningStatus.PROVISIONING, now) == 0) {
            return false; // another server took it
        }
        EnvironmentTemplate template = templateService.find(r.getTemplateId());
        try {
            String recordId = engines.get(template.getEngine()).provision(r, template);
            ProvisioningRequest fresh = repository.findById(r.getRequestId()).orElseThrow();
            fresh.setEngineRecordId(recordId);
            repository.save(fresh);
            auditService.logAction(fresh.getRequestedByUserId(), AuditAction.PROVISIONING_STARTED, "provisioning_request",
                    r.getRequestId(), r.getEnvironmentName(), "Started with " + template.getEngine() + " (record " + recordId + ")");
            return true;
        } catch (RuntimeException e) {
            log.warn("Provisioning {} could not start: {}", r.getRequestId(), e.getMessage());
            fail(r.getRequestId(), "Could not start: " + e.getMessage());
            return true;
        }
    }

    private boolean poll(ProvisioningRequest r) {
        if (r.getEngineRecordId() == null) {
            return false; // started a moment ago; the record id is being saved
        }
        EnvironmentTemplate template = templateService.find(r.getTemplateId());
        ProvisioningProgress progress;
        try {
            progress = engines.get(template.getEngine()).poll(template, r.getEngineRecordId());
        } catch (RuntimeException e) {
            log.warn("Provisioning {} could not be checked: {}", r.getRequestId(), e.getMessage());
            return false;
        }
        switch (progress.state()) {
            case FAILED -> {
                fail(r.getRequestId(), progress.message());
                return true;
            }
            case SUCCEEDED -> {
                return succeed(r.getRequestId(), template, progress.resourceId());
            }
            default -> {
                Timestamp since = r.getUpdatedAt() != null ? r.getUpdatedAt() : r.getCreatedAt();
                if (since != null && Duration.between(since.toInstant(), clock.instant()).toMinutes() >= timeout()) {
                    fail(r.getRequestId(), "Timed out after " + timeout() + " minutes; the provisioned product is left for an admin to inspect"
                            + (progress.resourceId() == null ? "" : " (" + progress.resourceId() + ")"));
                    return true;
                }
                return false;
            }
        }
    }

    private boolean succeed(String requestId, EnvironmentTemplate template, String resourceId) {
        Environment env;
        ProvisioningRequest done;
        try {
            Object[] result = tx.execute(status -> {
                ProvisioningRequest r = repository.findById(requestId).orElseThrow();
                if (r.getStatus() != ProvisioningStatus.PROVISIONING) {
                    return null;
                }
                CreateEnvironmentDTO dto = new CreateEnvironmentDTO();
                dto.setName(r.getEnvironmentName());
                dto.setDisplayName(r.getEnvironmentName());
                dto.setDescription("Provisioned from " + template.getName());
                ObjectNode metadata = objectMapper.createObjectNode();
                if (template.getRegion() != null) {
                    metadata.put("region", template.getRegion());
                }
                metadata.put("provisioningRequestId", requestId);
                dto.setMetadata(metadata.toString());
                dto.setAwsAccountRef(template.getAwsAccountRef());
                Environment created = environmentService.createEnvironment(dto, r.getRequestedByUserId());
                r.setEnvironmentId(created.getEnvironmentId());
                r.setEngineResourceId(resourceId);
                r.setDestroyAt(Timestamp.from(clock.instant().plus(Duration.ofHours(r.getTtlHours()))));
                r.setStatus(ProvisioningStatus.READY);
                return new Object[] {created, repository.save(r)};
            });
            if (result == null) {
                return false;
            }
            env = (Environment) result[0];
            done = (ProvisioningRequest) result[1];
        } catch (RuntimeException e) {
            log.warn("Provisioning {} succeeded in AWS but the environment could not be created: {}", requestId, e.getMessage());
            fail(requestId, "Built in AWS (" + resourceId + ") but the environment could not be created: " + e.getMessage());
            return true;
        }
        afterReady(done, env, template);
        return true;
    }

    /** Registers the new instances, gives the requester access and tells them; problems here are logged, not fatal. */
    private void afterReady(ProvisioningRequest r, Environment env, EnvironmentTemplate template) {
        try {
            discoveryService.discoverEnvironmentVms(env);
        } catch (RuntimeException e) {
            log.warn("Instances of provisioned environment {} not registered yet: {}", env.getName(), e.getMessage());
        }
        User requester = userRepository.findById(r.getRequestedByUserId()).orElse(null);
        if (requester != null && requester.getEmail() != null) {
            try {
                int days = (int) Math.max(1, Math.ceil(r.getTtlHours() / 24.0));
                String grantedBy = r.getReviewedByUserId() != null ? r.getReviewedByUserId() : r.getRequestedByUserId();
                accessService.grantAccess(env.getEnvironmentId(), grantedBy,
                        new GrantAccessDTO(requester.getEmail(), AccessLevel.ADMIN, days, "Provisioned from " + template.getName()));
            } catch (RuntimeException e) {
                log.warn("Requester of provisioned environment {} not granted access: {}", env.getName(), e.getMessage());
            }
        }
        auditService.logEnvironmentAction(r.getRequestedByUserId(), AuditAction.PROVISIONING_SUCCEEDED, env.getEnvironmentId(),
                env.getDisplayName(), "provisioning_request", r.getRequestId(), r.getEnvironmentName(),
                "Ready from " + template.getName() + "; destroyed at " + r.getDestroyAt());
        if (notificationService != null) {
            notificationService.notifyProvisioningOutcome(r.getRequestedByUserId(), r.getRequestId(), r.getEnvironmentName(),
                    env.getEnvironmentId(), true, "It is ready until " + r.getDestroyAt() + ".");
        }
        if (platformEvents != null) {
            platformEvents.publish(com.tcgdigital.vmcontrol.service.events.PlatformEventType.PROVISIONING_READY, env.getEnvironmentId(), env.getName(), java.util.Map.of(
                    "requestId", r.getRequestId(), "templateId", template.getTemplateId(), "requestedBy", r.getRequestedByUserId(),
                    "destroyAt", r.getDestroyAt() == null ? "" : r.getDestroyAt().toInstant().toString()));
        }
    }

    private void fail(String requestId, String message) {
        ProvisioningRequest r = repository.findById(requestId).orElse(null);
        if (r == null) {
            return;
        }
        r.setStatus(ProvisioningStatus.FAILED);
        r.setErrorMessage(message);
        repository.save(r);
        auditService.logAction(r.getRequestedByUserId(), AuditAction.PROVISIONING_FAILED, "provisioning_request", requestId,
                r.getEnvironmentName(), message);
        if (notificationService != null) {
            notificationService.notifyProvisioningOutcome(r.getRequestedByUserId(), requestId, r.getEnvironmentName(), null, false, message);
        }
    }

    Instant now() {
        return clock.instant();
    }
}
