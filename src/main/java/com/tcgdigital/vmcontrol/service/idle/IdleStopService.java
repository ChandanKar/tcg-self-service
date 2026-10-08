package com.tcgdigital.vmcontrol.service.idle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.IdleStopEvent;
import com.tcgdigital.vmcontrol.model.IdleStopMode;
import com.tcgdigital.vmcontrol.model.IdleStopOutcome;
import com.tcgdigital.vmcontrol.model.IdleStopRule;
import com.tcgdigital.vmcontrol.model.IdleStopSnooze;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.IdleStopEventRepository;
import com.tcgdigital.vmcontrol.repository.IdleStopRuleRepository;
import com.tcgdigital.vmcontrol.repository.IdleStopSnoozeRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.LockService;
import com.tcgdigital.vmcontrol.service.NotificationService;
import com.tcgdigital.vmcontrol.service.PricingReferenceService;
import com.tcgdigital.vmcontrol.service.VmOperationsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Evaluates idle auto-stop rules (E16-T03, G5). Exclusions are checked first and recorded as
 * SKIPPED / SNOOZED events (at most one per rule and reason per hour). When every running VM in
 * scope is idle, a DRY_RUN rule records one WOULD_STOP event per idle episode with the projected
 * saving; an ENFORCE rule starts a STOP operation (dependents first) as the rule's creator and
 * notifies the environment's members.
 */
@Service
public class IdleStopService {

    private static final Logger log = LoggerFactory.getLogger(IdleStopService.class);
    private static final Duration REPEAT_SKIP_AFTER = Duration.ofHours(1);

    private final IdleStopRuleRepository ruleRepository;
    private final IdleStopSnoozeRepository snoozeRepository;
    private final IdleStopEventRepository eventRepository;
    private final VmRepository vmRepository;
    private final OperationExecutionRepository executionRepository;
    private final UserRepository userRepository;
    private final VmInventorySnapshotRepository inventoryRepository;
    private final PricingReferenceService pricingReferenceService;
    private final LockService lockService;
    private final VmOperationsService vmOperationsService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final IdleEvaluator idleEvaluator;
    private final TransactionTemplate ruleTransaction;
    private final TransactionTemplate operationTransaction;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Clock clock = Clock.systemUTC();

    public IdleStopService(IdleStopRuleRepository ruleRepository, IdleStopSnoozeRepository snoozeRepository,
                           IdleStopEventRepository eventRepository, VmRepository vmRepository,
                           OperationExecutionRepository executionRepository, UserRepository userRepository,
                           VmInventorySnapshotRepository inventoryRepository,
                           PricingReferenceService pricingReferenceService, LockService lockService,
                           VmOperationsService vmOperationsService, NotificationService notificationService,
                           AuditService auditService, IdleEvaluator idleEvaluator,
                           PlatformTransactionManager transactionManager) {
        this.ruleRepository = ruleRepository;
        this.snoozeRepository = snoozeRepository;
        this.eventRepository = eventRepository;
        this.vmRepository = vmRepository;
        this.executionRepository = executionRepository;
        this.userRepository = userRepository;
        this.inventoryRepository = inventoryRepository;
        this.pricingReferenceService = pricingReferenceService;
        this.lockService = lockService;
        this.vmOperationsService = vmOperationsService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.idleEvaluator = idleEvaluator;
        this.ruleTransaction = new TransactionTemplate(transactionManager);
        this.ruleTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.operationTransaction = new TransactionTemplate(transactionManager);
        this.operationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Every enabled rule, each in its own transaction: one failing rule does not stop the rest. */
    public void evaluateAll() {
        List<String> ruleIds = ruleRepository.findByEnabledTrue().stream().map(IdleStopRule::getRuleId).toList();
        for (String ruleId : ruleIds) {
            try {
                evaluateRule(ruleId);
            } catch (Exception e) {
                log.error("Idle-stop rule {} failed: {}", ruleId, e.getMessage(), e);
            }
        }
    }

    /** Evaluate one rule now; returns the event recorded, if any. */
    public Optional<IdleStopEvent> evaluateRule(String ruleId) {
        return ruleTransaction.execute(status -> ruleRepository.findByIdFetchEnvironment(ruleId)
                .filter(rule -> Boolean.TRUE.equals(rule.getEnabled()))
                .flatMap(this::evaluate));
    }

    private Optional<IdleStopEvent> evaluate(IdleStopRule rule) {
        Instant now = clock.instant();
        Environment env = rule.getEnvironment();
        String envId = env.getEnvironmentId();

        // The rule acts as its creator: an offboarded or demoted creator switches it off (as E06-T04).
        User creator = userRepository.findById(rule.getCreatedByUserId()).orElse(null);
        if (creator == null || !Boolean.TRUE.equals(creator.getIsActive()) || !(creator.isAdmin() || creator.isEnvAdmin())) {
            rule.setEnabled(false);
            ruleRepository.save(rule);
            auditService.logEnvironmentAction(null, AuditAction.IDLE_STOP_RULE_CHANGED, envId, env.getName(),
                    "idle_stop_rule", rule.getRuleId(), env.getName(),
                    "Disabled: rule creator is no longer active or no longer an admin");
            return skip(rule, now, IdleStopOutcome.SKIPPED, "Disabled: rule creator is no longer active or no longer an admin");
        }
        if (!Boolean.TRUE.equals(env.getIsActive())) {
            return skip(rule, now, IdleStopOutcome.SKIPPED, "Environment inactive");
        }
        if (Boolean.TRUE.equals(env.getIsProduction())) {
            return skip(rule, now, IdleStopOutcome.SKIPPED, "Production environment");
        }
        List<IdleStopSnooze> snoozes = snoozeRepository.findActive(envId, Timestamp.from(now));
        if (!snoozes.isEmpty()) {
            return skip(rule, now, IdleStopOutcome.SNOOZED, "Snoozed until " + snoozes.get(0).getSnoozedUntil().toInstant());
        }
        if (lockService.isEnvironmentLocked(envId)) {
            String holder = lockService.getCurrentLock(envId)
                    .flatMap(l -> userRepository.findById(l.getLockedByUserId()))
                    .map(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getEmail())
                    .orElse("another user");
            return skip(rule, now, IdleStopOutcome.SKIPPED, "Locked by " + holder);
        }
        if (executionRepository.hasActiveOperations(envId)) {
            return skip(rule, now, IdleStopOutcome.SKIPPED, "Operation in progress");
        }

        List<Vm> vms = rule.getScopeType() == AutomationScopeType.GROUP
                ? vmRepository.findByGroupGroupIdOrderBySequencePositionAsc(rule.getGroupId())
                : vmRepository.findByEnvironmentIdFetchGroupAndEnvironment(envId);
        EnvironmentIdleResult result = idleEvaluator.evaluate(vms, rule, now);
        if (result.nothingRunning() || !result.allIdle()) {
            return Optional.empty();
        }

        Timestamp idleSince = Timestamp.from(result.idleSince().truncatedTo(ChronoUnit.SECONDS));
        List<Vm> running = vms.stream().filter(vm -> vm.getStatus() == VmStatus.RUNNING).toList();
        Saving saving = projectedSaving(running);
        String evidence = evidenceJson(result, saving);

        if (rule.getMode() == IdleStopMode.DRY_RUN) {
            if (eventRepository.existsByRuleIdAndOutcomeAndIdleSince(rule.getRuleId(), IdleStopOutcome.WOULD_STOP, idleSince)) {
                return Optional.empty(); // this idle episode is already recorded
            }
            return Optional.of(record(rule, now, IdleStopOutcome.WOULD_STOP,
                    "Dry run: would stop " + running.size() + " VM(s)", idleSince, evidence, saving.perHour(), null));
        }
        return enforce(rule, now, running, idleSince, evidence, saving);
    }

    private Optional<IdleStopEvent> enforce(IdleStopRule rule, Instant now, List<Vm> running, Timestamp idleSince,
                                            String evidence, Saving saving) {
        Environment env = rule.getEnvironment();
        String envId = env.getEnvironmentId();
        long idleMinutes = Duration.between(idleSince.toInstant(), now).toMinutes();
        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(OperationType.STOP);
        dto.setReason("Idle auto-stop: idle since " + idleSince.toInstant());
        dto.setSkipAlreadyInTargetState(true);
        dto.setContinueOnFailure(true);
        if (rule.getScopeType() == AutomationScopeType.GROUP) {
            dto.setGroupIds(List.of(rule.getGroupId()));
        }
        Optional<OperationExecution> started;
        try {
            // Own transaction: a refused start does not roll back the event recorded below.
            started = operationTransaction.execute(status ->
                    vmOperationsService.startOperationIfNeeded(envId, rule.getCreatedByUserId(), dto));
        } catch (Exception e) {
            log.warn("Idle-stop rule {} could not stop environment {}: {}", rule.getRuleId(), env.getName(), e.getMessage());
            return skip(rule, now, IdleStopOutcome.SKIPPED, truncate("Stop not started: " + e.getMessage()));
        }
        if (started == null || started.isEmpty()) {
            return Optional.empty();
        }
        String executionId = started.get().getExecutionId();
        IdleStopEvent event = record(rule, now, IdleStopOutcome.STOPPED,
                "Stopped " + running.size() + " VM(s) after " + idleMinutes + " minutes idle",
                idleSince, evidence, saving.perHour(), executionId);
        auditService.logEnvironmentAction(rule.getCreatedByUserId(), AuditAction.IDLE_STOP_TRIGGERED, envId, env.getName(),
                "operation", executionId, env.getName(),
                "Idle auto-stop of " + running.size() + " VM(s), idle since " + idleSince.toInstant()
                        + (saving.perHour().signum() > 0 ? ", saving about " + saving.perHour() + "/hour" : ""));
        try {
            notificationService.notifyIdleStopForEnvironment(envId, env.getName(), idleMinutes, running.size());
        } catch (Exception e) {
            log.warn("Could not notify idle stop of {}: {}", env.getName(), e.getMessage());
        }
        return Optional.of(event);
    }

    /** A SKIPPED / SNOOZED event, unless the same one was recorded for this rule in the last hour. */
    private Optional<IdleStopEvent> skip(IdleStopRule rule, Instant now, IdleStopOutcome outcome, String reason) {
        if (eventRepository.existsByRuleIdAndOutcomeAndReasonAndEvaluatedAtAfter(rule.getRuleId(), outcome, reason,
                Timestamp.from(now.minus(REPEAT_SKIP_AFTER)))) {
            return Optional.empty();
        }
        return Optional.of(record(rule, now, outcome, reason, null, null, null, null));
    }

    private IdleStopEvent record(IdleStopRule rule, Instant now, IdleStopOutcome outcome, String reason,
                                 Timestamp idleSince, String evidence, BigDecimal savingPerHour, String executionId) {
        IdleStopEvent event = new IdleStopEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setRuleId(rule.getRuleId());
        event.setEnvironmentId(rule.getEnvironment().getEnvironmentId());
        event.setEvaluatedAt(Timestamp.from(now.truncatedTo(ChronoUnit.SECONDS)));
        event.setOutcome(outcome);
        event.setReason(reason);
        event.setIdleSince(idleSince);
        event.setEvidenceJson(evidence);
        event.setProjectedSavingPerHour(savingPerHour);
        event.setExecutionId(executionId);
        return eventRepository.save(event);
    }

    record Saving(BigDecimal perHour, List<String> unpricedVmIds) {}

    /** Sum of the running VMs' hourly rates; a VM without a known price counts 0 and is listed. */
    private Saving projectedSaving(List<Vm> running) {
        if (running.isEmpty()) {
            return new Saving(BigDecimal.ZERO, List.of());
        }
        Map<String, String> types = inventoryRepository.findInstanceTypesByVmIds(running.stream().map(Vm::getVmId).toList())
                .stream().filter(p -> p.getInstanceType() != null)
                .collect(Collectors.toMap(VmInventorySnapshotRepository.InstanceTypeProjection::getVmId,
                        VmInventorySnapshotRepository.InstanceTypeProjection::getInstanceType, (a, b) -> a));
        BigDecimal total = BigDecimal.ZERO;
        List<String> unpriced = new java.util.ArrayList<>();
        for (Vm vm : running) {
            String type = types.get(vm.getVmId());
            PricingReferenceService.PriceLookupResult price = type == null ? PricingReferenceService.PriceLookupResult.unknown()
                    : pricingReferenceService.lookupHourlyRate(vm.getProvider().name(), type, vm.getRegion());
            if (price.priceKnown()) {
                total = total.add(price.hourlyRate());
            } else {
                unpriced.add(vm.getVmId());
            }
        }
        return new Saving(total.setScale(4, java.math.RoundingMode.HALF_UP), unpriced);
    }

    private String evidenceJson(EnvironmentIdleResult result, Saving saving) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("idleSince", result.idleSince());
        evidence.put("vms", result.vms());
        evidence.put("unpricedVmIds", saving.unpricedVmIds());
        try {
            return json.writeValueAsString(evidence);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String text) {
        return text != null && text.length() > 255 ? text.substring(0, 252) + "..." : text;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }
}
