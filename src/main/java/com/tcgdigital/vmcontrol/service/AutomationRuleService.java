package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.AutomationRuleDTO;
import com.tcgdigital.vmcontrol.dto.AutomationRuleRequestDTO;
import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.exception.LockAlreadyHeldException;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.AutomationRuleRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Business logic for AutomationRule — calendar-schedule and access-grant/lock-acquire
 * triggers that start or stop an environment (optionally narrowed to a group or VM).
 *
 * A rule never breaks an active lock: firing goes through the same
 * {@link VmOperationsService#startOperation} entry point manual start/stop uses, so a
 * locked-by-someone-else environment or an in-progress operation is simply skipped and
 * the rule owner is notified — never overridden.
 */
@Service
public class AutomationRuleService {

    private static final Logger log = LoggerFactory.getLogger(AutomationRuleService.class);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> WEEK = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private final AutomationRuleRepository automationRuleRepository;
    private final EnvironmentRepository environmentRepository;
    private final VmGroupRepository vmGroupRepository;
    private final VmRepository vmRepository;
    private final VmOperationsService vmOperationsService;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final UserService userService;
    private final UserRepository userRepository;
    /** Starts a rule's operation in its own transaction, so its failure cannot poison the caller's. */
    private final TransactionTemplate operationTransaction;
    /** Evaluates one schedule rule per transaction. */
    private final TransactionTemplate ruleTransaction;
    private final ScheduleCalculator scheduleCalculator;

    /** How late a scheduler tick may still fire a schedule time (catch-up window). */
    @Value("${automation.rules.catch-up-minutes:15}")
    private long catchUpMinutes = 15;

    /** Replaceable in tests. */
    private Clock clock = Clock.systemDefaultZone();

    /** How a rule's attempt to start an operation ended (C5). */
    enum FireOutcome { STARTED, NOTHING_TO_DO, LOCKED, BUSY, SKIPPED, FAILED }

    public AutomationRuleService(AutomationRuleRepository automationRuleRepository,
                                  EnvironmentRepository environmentRepository,
                                  VmGroupRepository vmGroupRepository,
                                  VmRepository vmRepository,
                                  VmOperationsService vmOperationsService,
                                  AuditService auditService,
                                  NotificationService notificationService,
                                  UserService userService,
                                  PlatformTransactionManager transactionManager,
                                  ScheduleCalculator scheduleCalculator,
                                  UserRepository userRepository) {
        this.userRepository = userRepository;
        this.scheduleCalculator = scheduleCalculator;
        this.ruleTransaction = new TransactionTemplate(transactionManager);
        this.ruleTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.operationTransaction = new TransactionTemplate(transactionManager);
        this.operationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.automationRuleRepository = automationRuleRepository;
        this.environmentRepository = environmentRepository;
        this.vmGroupRepository = vmGroupRepository;
        this.vmRepository = vmRepository;
        this.vmOperationsService = vmOperationsService;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.userService = userService;
    }

    // ============= CRUD =============

    @Transactional(readOnly = true)
    public List<AutomationRuleDTO> listRules(String environmentId) {
        // Environments are fetched with the rules: loading them lazily per rule raced with
        // environment deletion and failed the whole list (PL-07).
        List<AutomationRule> rules = (environmentId == null || environmentId.isBlank())
                ? automationRuleRepository.findAllFetchEnvironment()
                : automationRuleRepository.findByEnvironmentFetchEnvironment(environmentId);
        return rules.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public AutomationRuleDTO getRule(String ruleId) {
        return toDto(getRuleEntity(ruleId));
    }

    @Transactional
    public AutomationRuleDTO createRule(String createdByUserId, AutomationRuleRequestDTO dto) {
        Environment environment = environmentRepository.findById(dto.getEnvironmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Environment", dto.getEnvironmentId()));
        validateScope(environment, dto.getScopeType(), dto.getScopeId());
        validateTriggerFields(dto);

        AutomationRule rule = new AutomationRule();
        rule.setRuleId(UUID.randomUUID().toString());
        rule.setEnvironment(environment);
        applyRequestFields(rule, dto);
        rule.setCreatedByUserId(createdByUserId);

        rule = automationRuleRepository.save(rule);

        auditService.logAutomationRuleCreated(createdByUserId, rule.getRuleId(), rule.getName(),
                environment.getEnvironmentId(), environment.getName());

        return toDto(rule);
    }

    @Transactional
    public AutomationRuleDTO updateRule(String ruleId, String updatedByUserId, AutomationRuleRequestDTO dto) {
        AutomationRule rule = getRuleEntity(ruleId);
        Environment environment = rule.getEnvironment();
        validateScope(environment, dto.getScopeType(), dto.getScopeId());
        validateTriggerFields(dto);

        applyRequestFields(rule, dto);
        rule = automationRuleRepository.save(rule);

        auditService.logAutomationRuleUpdated(updatedByUserId, rule.getRuleId(), rule.getName(),
                environment.getEnvironmentId(), environment.getName());

        return toDto(rule);
    }

    @Transactional
    public void deleteRule(String ruleId, String actorUserId) {
        AutomationRule rule = getRuleEntity(ruleId);
        Environment environment = rule.getEnvironment();
        automationRuleRepository.delete(rule);
        auditService.logAutomationRuleDeleted(actorUserId, ruleId, rule.getName(),
                environment.getEnvironmentId(), environment.getName());
    }

    @Transactional
    public AutomationRuleDTO setEnabled(String ruleId, boolean enabled, String actorUserId) {
        AutomationRule rule = getRuleEntity(ruleId);
        if (enabled) {
            // Re-enabling is refused while the reason it cannot run still holds (M16).
            Optional<Preflight> blocked = preflightProblem(rule);
            if (blocked.isPresent()) {
                throw new ValidationException("This rule cannot be enabled: " + blocked.get().message());
            }
            rule.setDisabledReason(null);
        }
        rule.setEnabled(enabled);
        rule = automationRuleRepository.save(rule);
        auditService.logAutomationRuleUpdated(actorUserId, rule.getRuleId(), rule.getName(),
                rule.getEnvironment().getEnvironmentId(), rule.getEnvironment().getName());
        return toDto(rule);
    }

    private AutomationRule getRuleEntity(String ruleId) {
        return automationRuleRepository.findById(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("AutomationRule", ruleId));
    }

    // ============= Access-grant / lock-acquire hooks =============
    // Best-effort: called from LockService/EnvironmentAccessService wrapped in their own
    // try/catch side-effect helpers, so a failure here never blocks the caller's transaction.

    /**
     * Called right after {@code userId} acquires the environment lock (starts a work session).
     * Fires as {@code userId} itself — required for {@code verifyLockPermission} to see them
     * as the current lock holder rather than skipping immediately.
     */
    public void handleLockAcquired(String environmentId, String userId) {
        List<AutomationRule> rules = automationRuleRepository.findEnabledAccessGrantRulesFetchEnvironment(
                AutomationTriggerType.ACCESS_GRANT, AccessGrantMode.LOCK_ACQUIRE, environmentId);
        for (AutomationRule rule : rules) {
            fireOperation(rule, OperationType.START, userId, "lock acquired");
        }
    }

    /**
     * Called right after an access request is approved / access is granted directly.
     * Fires as the rule's own creator — the newly-granted user hasn't necessarily started a
     * session yet, so there's no other real, authorized actor available.
     */
    public void handleAccessGranted(String environmentId) {
        List<AutomationRule> rules = automationRuleRepository.findEnabledAccessGrantRulesFetchEnvironment(
                AutomationTriggerType.ACCESS_GRANT, AccessGrantMode.ACCESS_APPROVED, environmentId);
        for (AutomationRule rule : rules) {
            fireOperation(rule, OperationType.START, rule.getCreatedByUserId(), "access approved");
        }
    }

    // ============= Scheduler-driven evaluation =============

    /**
     * Evaluated on every scheduler tick. Each rule runs in its own transaction with its
     * environment fetched (C6: outside a web request the lazy environment was unreadable), and
     * one rule's failure does not sink the batch.
     */
    public void evaluateSchedules() {
        for (String ruleId : automationRuleRepository.findEnabledIdsByTriggerType(AutomationTriggerType.SCHEDULE)) {
            try {
                ruleTransaction.executeWithoutResult(status -> automationRuleRepository
                        .findByIdFetchEnvironment(ruleId).ifPresent(this::evaluateAndFireOneSchedule));
            } catch (Exception e) {
                log.error("Error evaluating automation rule {}: {}", ruleId, e.getMessage(), e);
            }
        }
    }

    private void evaluateAndFireOneSchedule(AutomationRule rule) {
        ZonedDateTime now;
        try {
            now = ZonedDateTime.now(clock.withZone(ZoneId.of(rule.getTimezone())));
        } catch (Exception e) {
            log.warn("Automation rule {} has an invalid timezone '{}' — skipping evaluation",
                    rule.getRuleId(), rule.getTimezone());
            return;
        }
        Set<DayOfWeek> days = scheduleCalculator.parseDays(rule.getDaysOfWeek());
        Duration catchUp = Duration.ofMinutes(catchUpMinutes);

        evaluateTime(rule, OperationType.STOP, rule.getStopTime(), toLocalDate(rule.getLastStopFiredOn()),
                now, days, catchUp, day -> rule.setLastStopFiredOn(Date.valueOf(day)));
        evaluateTime(rule, OperationType.START, rule.getStartTime(), toLocalDate(rule.getLastStartFiredOn()),
                now, days, catchUp, day -> rule.setLastStartFiredOn(Date.valueOf(day)));
    }

    /**
     * Fire one of a rule's times when due (catching up a late tick), marking the day fired only
     * when the rule started something or had nothing to do; a locked, busy or failed attempt is
     * retried on the next tick until the window closes, then recorded as missed (H26).
     */
    private void evaluateTime(AutomationRule rule, OperationType type, String hhmm, LocalDate lastFiredOn,
                              ZonedDateTime now, Set<DayOfWeek> days, Duration catchUp,
                              java.util.function.Consumer<LocalDate> markFired) {
        if (hhmm == null || hhmm.isBlank()) {
            return;
        }
        LocalTime time = LocalTime.parse(hhmm, HHMM);
        LocalDate today = now.toLocalDate();
        if (scheduleCalculator.isDue(now, time, days, lastFiredOn, catchUp)) {
            FireOutcome outcome = fireOperation(rule, type, rule.getCreatedByUserId(), "schedule");
            if (outcome == FireOutcome.STARTED || outcome == FireOutcome.NOTHING_TO_DO) {
                markFired.accept(today);
                automationRuleRepository.save(rule);
            }
        } else if (scheduleCalculator.isMissed(now, time, days, lastFiredOn, catchUp)
                && existedBefore(rule, scheduleCalculator.dueInstant(today, time, now))) {
            boolean triedToday = rule.getLastRunAt() != null
                    && rule.getLastRunAt().toInstant().isAfter(scheduleCalculator.dueInstant(today, time, now).toInstant());
            String reason = triedToday ? rule.getLastRunDetail() : "the scheduler did not run at " + hhmm;
            AutomationRunStatus status = triedToday && rule.getLastRunStatus() == AutomationRunStatus.FAILED
                    ? AutomationRunStatus.FAILED : AutomationRunStatus.SKIPPED;
            recordRun(rule, status, AutomationRunReason.MISSED_WINDOW, "Missed window: " + reason);
            markFired.accept(today);
            automationRuleRepository.save(rule);
        }
    }

    /**
     * A rule created after a time passed should not report that time as missed. (Not updatedAt:
     * recording a run changes it too.)
     */
    private static boolean existedBefore(AutomationRule rule, ZonedDateTime due) {
        return rule.getCreatedAt() == null || rule.getCreatedAt().toInstant().isBefore(due.toInstant());
    }

    private LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    // ============= Shared firing logic =============

    FireOutcome fireOperation(AutomationRule rule, OperationType operationType,
                              String actingUserId, String triggerLabel) {
        Optional<Preflight> problem = preflightProblem(rule);
        if (problem.isPresent()) {
            disable(rule, problem.get());
            return FireOutcome.SKIPPED;
        }
        Environment environment = rule.getEnvironment();
        String environmentId = environment.getEnvironmentId();
        String environmentName = environment.getName();

        StartOperationDTO dto = new StartOperationDTO();
        dto.setOperationType(operationType);
        dto.setReason("Automation rule: " + rule.getName() + " (" + triggerLabel + ")");
        dto.setSkipAlreadyInTargetState(Boolean.TRUE.equals(rule.getSkipIfAlreadyInTargetState()));
        dto.setContinueOnFailure(true);
        if (rule.getScopeType() == AutomationScopeType.GROUP) {
            dto.setGroupIds(List.of(rule.getScopeId()));
        } else if (rule.getScopeType() == AutomationScopeType.VM) {
            dto.setVmIds(List.of(rule.getScopeId()));
        }

        // The operation runs in its own transaction; the run record and audit below are written
        // outside it, so they persist even when the operation transaction rolls back.
        FireOutcome outcome;
        String detail;
        String lockedByDisplayName = null;
        try {
            Optional<?> started = operationTransaction.execute(status ->
                    vmOperationsService.startOperationIfNeeded(environmentId, actingUserId, dto));
            if (started != null && started.isPresent()) {
                outcome = FireOutcome.STARTED;
                detail = "Triggered " + operationType + " (" + triggerLabel + ")";
            } else {
                outcome = FireOutcome.NOTHING_TO_DO;
                detail = "Nothing to do: all targets already in the requested state";
            }
        } catch (LockAlreadyHeldException e) {
            lockedByDisplayName = resolveDisplayName(e.getLockedByUserId());
            outcome = FireOutcome.LOCKED;
            detail = "Environment locked by " + lockedByDisplayName;
        } catch (ValidationException e) {
            outcome = e.getMessage() != null && e.getMessage().contains("already in progress")
                    ? FireOutcome.BUSY : FireOutcome.SKIPPED;
            detail = e.getMessage();
        } catch (Exception e) {
            log.error("Automation rule {} failed to fire: {}", rule.getRuleId(), e.getMessage(), e);
            outcome = FireOutcome.FAILED;
            detail = e.getMessage();
        }

        // A repeat of the same skip (e.g. every tick while the environment stays locked) is not
        // recorded, audited or notified again.
        if (outcome != FireOutcome.STARTED && rule.getLastRunDetail() != null
                && rule.getLastRunDetail().equals(detail)
                && rule.getLastRunStatus() == (outcome == FireOutcome.FAILED ? AutomationRunStatus.FAILED
                        : AutomationRunStatus.SKIPPED)) {
            return outcome;
        }

        switch (outcome) {
            case STARTED -> {
                recordRun(rule, AutomationRunStatus.SUCCESS, AutomationRunReason.OK, detail);
                auditService.logAutomationRuleTriggered(actingUserId, rule.getRuleId(), rule.getName(),
                        environmentId, environmentName, detail);
            }
            case FAILED -> {
                recordRun(rule, AutomationRunStatus.FAILED, AutomationRunReason.ERROR, detail);
                auditService.logAutomationRuleFailed(actingUserId, rule.getRuleId(), rule.getName(),
                        environmentId, environmentName, detail);
            }
            default -> {
                recordRun(rule, AutomationRunStatus.SKIPPED, switch (outcome) {
                    case NOTHING_TO_DO -> AutomationRunReason.NOTHING_TO_DO;
                    case LOCKED -> AutomationRunReason.LOCKED;
                    case BUSY -> AutomationRunReason.OPERATION_IN_PROGRESS;
                    default -> AutomationRunReason.ERROR;
                }, detail);
                auditService.logAutomationRuleSkipped(actingUserId, rule.getRuleId(), rule.getName(),
                        environmentId, environmentName, detail);
                if (outcome == FireOutcome.LOCKED) {
                    notificationService.notifyAutomationRuleSkipped(rule.getCreatedByUserId(), environmentName,
                            rule.getName(), lockedByDisplayName);
                }
            }
        }
        return outcome;
    }

    /** Detail is cut to the 500-character column (LOW-AUTO-LASTRUNDETAIL). */
    private void recordRun(AutomationRule rule, AutomationRunStatus status, AutomationRunReason reason, String detail) {
        rule.setLastRunAt(Timestamp.from(Instant.now()));
        rule.setLastRunStatus(status);
        rule.setLastRunReason(reason);
        rule.setLastRunDetail(detail != null && detail.length() > MAX_RUN_DETAIL
                ? detail.substring(0, MAX_RUN_DETAIL - 3) + "..." : detail);
        automationRuleRepository.save(rule);
    }

    private static final int MAX_RUN_DETAIL = 500;

    /** Why a rule cannot act at all (as opposed to a skip that may succeed next time). */
    record Preflight(AutomationRunReason reason, String message) {}

    /**
     * M16: a rule must not keep acting for a deactivated environment, an offboarded or demoted
     * creator (it acts as them), or a group/VM that no longer exists.
     */
    Optional<Preflight> preflightProblem(AutomationRule rule) {
        if (!Boolean.TRUE.equals(rule.getEnvironment().getIsActive())) {
            return Optional.of(new Preflight(AutomationRunReason.ENVIRONMENT_INACTIVE, "Environment deactivated"));
        }
        User creator = userRepository.findById(rule.getCreatedByUserId()).orElse(null);
        if (creator == null || !Boolean.TRUE.equals(creator.getIsActive())
                || !(creator.isAdmin() || creator.isEnvAdmin())) {
            return Optional.of(new Preflight(AutomationRunReason.CREATOR_INACTIVE,
                    "Rule creator is no longer active or no longer an admin"));
        }
        if (rule.getScopeType() == AutomationScopeType.GROUP) {
            boolean exists = rule.getScopeId() != null && vmGroupRepository.existsById(rule.getScopeId());
            if (!exists) {
                return Optional.of(new Preflight(AutomationRunReason.SCOPE_MISSING, "Target group no longer exists"));
            }
        } else if (rule.getScopeType() == AutomationScopeType.VM) {
            boolean exists = rule.getScopeId() != null && vmRepository.findById(rule.getScopeId())
                    .filter(v -> Boolean.TRUE.equals(v.getIsActive())).isPresent();
            if (!exists) {
                return Optional.of(new Preflight(AutomationRunReason.SCOPE_MISSING, "Target VM no longer exists"));
            }
        }
        return Optional.empty();
    }

    /** Switch a rule off with its reason, record and audit it (and tell admins about a creator problem). */
    private void disable(AutomationRule rule, Preflight problem) {
        rule.setEnabled(false);
        rule.setDisabledReason(problem.message());
        recordRun(rule, AutomationRunStatus.SKIPPED, problem.reason(), "Disabled: " + problem.message());
        Environment environment = rule.getEnvironment();
        log.warn("Automation rule {} disabled: {}", rule.getRuleId(), problem.message());
        auditService.logEnvironmentAction(null, AuditAction.AUTOMATION_RULE_UPDATED, environment.getEnvironmentId(),
                environment.getName(), "automation_rule", rule.getRuleId(), rule.getName(),
                "Disabled: " + problem.message());
        if (problem.reason() == AutomationRunReason.CREATOR_INACTIVE) {
            try {
                notificationService.notifyAutomationRuleDisabled(environment.getName(), rule.getName(), problem.message());
            } catch (Exception e) {
                log.warn("Could not notify admins about disabled rule {}: {}", rule.getRuleId(), e.getMessage());
            }
        }
    }

    private String resolveDisplayName(String userId) {
        if (userId == null) {
            return "another user";
        }
        try {
            User user = userService.getUserById(userId);
            return user.getDisplayName() != null ? user.getDisplayName() : userId;
        } catch (Exception e) {
            return userId;
        }
    }

    // ============= Validation =============

    private void validateScope(Environment environment, AutomationScopeType scopeType, String scopeId) {
        if (scopeType == AutomationScopeType.GROUP) {
            if (scopeId == null || scopeId.isBlank()) {
                throw new ValidationException("A group must be selected for GROUP scope");
            }
            VmGroup group = vmGroupRepository.findById(scopeId)
                    .orElseThrow(() -> new ValidationException("Group not found: " + scopeId));
            if (!group.getEnvironment().getEnvironmentId().equals(environment.getEnvironmentId())) {
                throw new ValidationException("Selected group does not belong to environment " + environment.getName());
            }
        } else if (scopeType == AutomationScopeType.VM) {
            if (scopeId == null || scopeId.isBlank()) {
                throw new ValidationException("A VM must be selected for VM scope");
            }
            Vm vm = vmRepository.findById(scopeId)
                    .orElseThrow(() -> new ValidationException("VM not found: " + scopeId));
            if (!vm.getGroup().getEnvironment().getEnvironmentId().equals(environment.getEnvironmentId())) {
                throw new ValidationException("Selected VM does not belong to environment " + environment.getName());
            }
        }
    }

    private void validateTriggerFields(AutomationRuleRequestDTO dto) {
        if (dto.getTriggerType() == AutomationTriggerType.SCHEDULE) {
            if (dto.getDaysOfWeek() == null || dto.getDaysOfWeek().isEmpty()) {
                throw new ValidationException("At least one day of week is required for a schedule rule");
            }
            for (String day : dto.getDaysOfWeek()) {
                if (day == null || !WEEK.contains(day.trim().toUpperCase(java.util.Locale.ROOT))) {
                    throw new ValidationException("Unknown day: " + day);
                }
            }
            boolean hasStop = isValidTime(dto.getStopTime());
            boolean hasStart = isValidTime(dto.getStartTime());
            if (!hasStop && !hasStart) {
                throw new ValidationException("At least one of stop time or start time is required for a schedule rule");
            }
            if (dto.getStopTime() != null && !dto.getStopTime().isBlank() && !hasStop) {
                throw new ValidationException("Stop time must be in HH:mm format");
            }
            if (dto.getStartTime() != null && !dto.getStartTime().isBlank() && !hasStart) {
                throw new ValidationException("Start time must be in HH:mm format");
            }
            if (hasStop && hasStart && dto.getStopTime().equals(dto.getStartTime())) {
                throw new ValidationException("Start and stop times must differ");
            }
            if (dto.getTimezone() == null || dto.getTimezone().isBlank()) {
                throw new ValidationException("Timezone is required for a schedule rule");
            }
            try {
                ZoneId.of(dto.getTimezone());
            } catch (Exception e) {
                throw new ValidationException("Invalid timezone: " + dto.getTimezone());
            }
        } else if (dto.getTriggerType() == AutomationTriggerType.ACCESS_GRANT) {
            if (dto.getAccessGrantMode() == null) {
                throw new ValidationException("Access grant mode is required for an access-grant rule");
            }
        }
    }

    private boolean isValidTime(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            HHMM.parse(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    // ============= Mapping helpers =============

    private void applyRequestFields(AutomationRule rule, AutomationRuleRequestDTO dto) {
        rule.setName(dto.getName());
        rule.setDescription(dto.getDescription());
        rule.setScopeType(dto.getScopeType());
        rule.setScopeId(dto.getScopeType() == AutomationScopeType.ENVIRONMENT ? null : dto.getScopeId());
        rule.setTriggerType(dto.getTriggerType());

        if (dto.getTriggerType() == AutomationTriggerType.SCHEDULE) {
            // Deduplicated, in week order.
            java.util.Set<String> chosen = dto.getDaysOfWeek().stream()
                    .map(d -> d.trim().toUpperCase(java.util.Locale.ROOT))
                    .collect(Collectors.toSet());
            rule.setDaysOfWeek(WEEK.stream().filter(chosen::contains).collect(Collectors.joining(",")));
            rule.setStopTime(blankToNull(dto.getStopTime()));
            rule.setStartTime(blankToNull(dto.getStartTime()));
            rule.setTimezone(dto.getTimezone());
            rule.setAccessGrantMode(null);
        } else {
            rule.setDaysOfWeek(null);
            rule.setStopTime(null);
            rule.setStartTime(null);
            rule.setTimezone(null);
            rule.setAccessGrantMode(dto.getAccessGrantMode());
        }

        rule.setSkipIfAlreadyInTargetState(dto.isSkipIfAlreadyInTargetState());
        rule.setEnabled(dto.isEnabled());
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private AutomationRuleDTO toDto(AutomationRule rule) {
        Environment environment = rule.getEnvironment();
        String scopeName = resolveScopeName(rule);
        String createdByDisplayName = resolveDisplayName(rule.getCreatedByUserId());
        return AutomationRuleDTO.fromEntity(rule, environment.getName(), scopeName, createdByDisplayName);
    }

    private String resolveScopeName(AutomationRule rule) {
        if (rule.getScopeType() == AutomationScopeType.GROUP && rule.getScopeId() != null) {
            return vmGroupRepository.findById(rule.getScopeId())
                    .map(g -> g.getDisplayName() != null ? g.getDisplayName() : g.getName())
                    .orElse(rule.getScopeId());
        }
        if (rule.getScopeType() == AutomationScopeType.VM && rule.getScopeId() != null) {
            return vmRepository.findById(rule.getScopeId())
                    .map(Vm::getName)
                    .orElse(rule.getScopeId());
        }
        return rule.getEnvironment().getName();
    }
}
