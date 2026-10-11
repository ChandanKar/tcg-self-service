package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.BudgetActionRequestDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Budget;
import com.tcgdigital.vmcontrol.model.BudgetActionRequest;
import com.tcgdigital.vmcontrol.model.BudgetActionType;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.BudgetActionRequestRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Approval-gated budget actions (E20-T03). When an environment budget is exhausted its action is
 * only <em>requested</em>; an owner (an admin, or someone with an effective ADMIN level on the
 * environment) approves or rejects it. The budget's creator may not approve its own action
 * unless they are an admin (four eyes where possible). An approved freeze blocks starts until the
 * first instant of the next month in the cost day zone; unapproved requests expire at month end.
 */
@Service
public class BudgetActionService {

    private static final Logger log = LoggerFactory.getLogger(BudgetActionService.class);
    private static final int MAX_REASON = 500;

    private final BudgetActionRequestRepository requestRepository;
    private final EnvironmentRepository environmentRepository;
    private final UserRepository userRepository;
    private final SecurityService securityService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final CostDayBoundary dayBoundary;
    private final VmOperationsService vmOperationsService;
    /** A stop runs in its own transaction, so a refused stop cannot roll back the approval (E20-T04). */
    private final org.springframework.transaction.support.TransactionTemplate stopTransaction;

    /** Prefix of the decision reason of an approved stop still waiting to run. */
    static final String STOP_PENDING = "Stop pending: ";

    /** Replaceable in tests. */
    private Clock clock = Clock.systemUTC();

    public BudgetActionService(BudgetActionRequestRepository requestRepository, EnvironmentRepository environmentRepository,
                               UserRepository userRepository, SecurityService securityService,
                               NotificationService notificationService, AuditService auditService,
                               CostDayBoundary dayBoundary, VmOperationsService vmOperationsService,
                               org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.vmOperationsService = vmOperationsService;
        this.stopTransaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.stopTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requestRepository = requestRepository;
        this.environmentRepository = environmentRepository;
        this.userRepository = userRepository;
        this.securityService = securityService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.dayBoundary = dayBoundary;
    }

    // ============= Raise (from the evaluation) =============

    /** Requests the budget's action for the month once; never acts by itself. True when new. */
    @Transactional
    public boolean raise(Budget budget, YearMonth month) {
        if (budget.getActionType() == BudgetActionType.NONE || budget.getScopeType() != BudgetScopeType.ENVIRONMENT) {
            return false;
        }
        if (requestRepository.findByBudgetBudgetIdAndBudgetMonthAndActionType(budget.getBudgetId(), month.toString(),
                budget.getActionType()).isPresent()) {
            return false;
        }
        BudgetActionRequest request = new BudgetActionRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setBudget(budget);
        request.setBudgetMonth(month.toString());
        request.setActionType(budget.getActionType());
        try {
            requestRepository.saveAndFlush(request);
        } catch (DataIntegrityViolationException e) {
            return false; // raised by a concurrent run
        }
        Environment environment = environment(budget.getScopeId());
        try {
            notificationService.notifyBudgetActionPending(environment.getEnvironmentId(), name(environment),
                    request.getRequestId(), budget.getActionType(), month);
        } catch (Exception e) {
            log.warn("Could not notify owners of budget action {}: {}", request.getRequestId(), e.getMessage());
        }
        auditService.logEnvironmentAction(null, AuditAction.BUDGET_ACTION_REQUESTED, environment.getEnvironmentId(),
                name(environment), "budget_action", request.getRequestId(), describe(budget.getActionType()),
                "Budget exhausted for " + month + "; " + describe(budget.getActionType()) + " awaits an owner's approval");
        return true;
    }

    /** Pending requests of earlier months become EXPIRED. */
    @Transactional
    public int expireBefore(YearMonth month) {
        List<BudgetActionRequest> stale = requestRepository.findPendingBefore(month.toString());
        stale.forEach(r -> {
            r.setStatus(BudgetActionRequest.Status.EXPIRED);
            r.setDecidedAt(Timestamp.from(clock.instant()));
            r.setDecisionReason("Not approved before the month ended");
        });
        requestRepository.saveAll(stale);
        return stale.size();
    }

    // ============= Reads =============

    /** Admins see all; others the requests of environments they administer. */
    @Transactional(readOnly = true)
    public List<BudgetActionRequestDTO> list(String userId, BudgetActionRequest.Status status) {
        User user = user(userId);
        return requestRepository.findAllFetchBudget().stream()
                .filter(r -> status == null || r.getStatus() == status)
                .filter(r -> canDecide(user, r.getBudget().getScopeId()))
                .map(this::toDto)
                .toList();
    }

    // ============= Decisions =============

    @Transactional
    public BudgetActionRequestDTO approve(String requestId, String userId, String reason) {
        BudgetActionRequest request = pending(requestId);
        Budget budget = request.getBudget();
        User user = user(userId);
        assertCanDecide(user, budget.getScopeId());
        if (!user.isAdmin() && userId.equals(budget.getCreatedByUserId())) {
            throw new UnauthorizedException("You set this budget; another owner or an admin must approve its action");
        }
        decide(request, BudgetActionRequest.Status.APPROVED, userId, reason);
        Environment environment = environment(budget.getScopeId());
        if (request.getActionType() == BudgetActionType.FREEZE_STARTS) {
            Instant until = YearMonth.parse(request.getBudgetMonth()).plusMonths(1).atDay(1)
                    .atStartOfDay(dayBoundary.zone()).toInstant();
            environment.setStartFrozenUntil(Timestamp.from(until));
            environmentRepository.save(environment);
            try {
                notificationService.notifyBudgetFreeze(environment.getEnvironmentId(), name(environment), until);
            } catch (Exception e) {
                log.warn("Could not notify members of the freeze of {}: {}", environment.getEnvironmentId(), e.getMessage());
            }
        }
        auditService.logEnvironmentAction(userId, AuditAction.BUDGET_ACTION_APPROVED, environment.getEnvironmentId(),
                name(environment), "budget_action", requestId, describe(request.getActionType()),
                "Approved " + describe(request.getActionType()) + reasonSuffix(reason));
        if (request.getActionType() == BudgetActionType.STOP_ENVIRONMENT) {
            String stopReason = "Budget exhausted: " + name(environment) + " (approved by " + displayName(user) + ")";
            try {
                notificationService.notifyStopEnvironment(environment.getEnvironmentId(), name(environment), userId, stopReason);
            } catch (Exception e) {
                log.warn("Could not send the stop notice for {}: {}", environment.getEnvironmentId(), e.getMessage());
            }
            runStop(request, environment, userId, stopReason);
        }
        return toDto(request);
    }

    /**
     * Starts the STOP operation of an approved stop action as the approver, trigger BUDGET (E20-T04).
     * When the environment is locked by someone else or busy, the request stays APPROVED with a
     * "Stop pending" reason, the approver is told, and the next evaluation retries.
     */
    private void runStop(BudgetActionRequest request, Environment environment, String approverId, String stopReason) {
        com.tcgdigital.vmcontrol.dto.StartOperationDTO dto = new com.tcgdigital.vmcontrol.dto.StartOperationDTO();
        dto.setOperationType(com.tcgdigital.vmcontrol.model.OperationType.STOP);
        dto.setReason(stopReason);
        dto.setSkipAlreadyInTargetState(true);
        dto.setContinueOnFailure(true);
        String pending = null;
        try {
            Optional<com.tcgdigital.vmcontrol.model.OperationExecution> started = stopTransaction.execute(status ->
                    vmOperationsService.startOperationIfNeeded(environment.getEnvironmentId(), approverId, dto,
                            com.tcgdigital.vmcontrol.model.OperationTriggerSource.BUDGET));
            if (started != null && started.isPresent()) {
                request.setExecutionId(started.get().getExecutionId());
                request.setDecisionReason(trimReason(stripPending(request.getDecisionReason())));
            } else {
                request.setDecisionReason(trimReason("Nothing to stop: every VM was already stopped"));
            }
        } catch (com.tcgdigital.vmcontrol.exception.LockAlreadyHeldException e) {
            pending = "environment locked by " + userRepository.findById(e.getLockedByUserId())
                    .map(BudgetActionService::displayName).orElse("another user");
        } catch (ValidationException e) {
            pending = e.getMessage();
        }
        if (pending != null) {
            request.setDecisionReason(trimReason(STOP_PENDING + pending));
            try {
                notificationService.notifyBudgetStopDeferred(approverId, environment.getEnvironmentId(), name(environment), pending);
            } catch (Exception e) {
                log.warn("Could not tell the approver why the stop waits: {}", e.getMessage());
            }
        }
        requestRepository.save(request);
    }

    /** Approved stops that could not run yet are tried again (from the daily evaluation). */
    @Transactional
    public int retryPendingStops() {
        int started = 0;
        for (BudgetActionRequest request : requestRepository.findApprovedStopsWaiting(STOP_PENDING + "%")) {
            Environment environment = environment(request.getBudget().getScopeId());
            String approverId = request.getDecidedByUserId();
            String approver = userRepository.findById(approverId).map(BudgetActionService::displayName).orElse(approverId);
            runStop(request, environment, approverId, "Budget exhausted: " + name(environment) + " (approved by " + approver + ")");
            if (request.getExecutionId() != null) {
                started++;
            }
        }
        return started;
    }

    private static String stripPending(String reason) {
        return reason != null && reason.startsWith(STOP_PENDING) ? null : reason;
    }

    private static String trimReason(String reason) {
        return reason == null ? null : reason.substring(0, Math.min(reason.length(), MAX_REASON));
    }

    private static String displayName(User user) {
        return user.getDisplayName() != null ? user.getDisplayName() : user.getEmail();
    }

    @Transactional
    public BudgetActionRequestDTO reject(String requestId, String userId, String reason) {
        BudgetActionRequest request = pending(requestId);
        assertCanDecide(user(userId), request.getBudget().getScopeId());
        decide(request, BudgetActionRequest.Status.REJECTED, userId, reason);
        Environment environment = environment(request.getBudget().getScopeId());
        auditService.logEnvironmentAction(userId, AuditAction.BUDGET_ACTION_REJECTED, environment.getEnvironmentId(),
                name(environment), "budget_action", requestId, describe(request.getActionType()),
                "Rejected " + describe(request.getActionType()) + reasonSuffix(reason));
        return toDto(request);
    }

    /** Ends a start freeze early. */
    @Transactional
    public void liftFreeze(String environmentId, String userId, String reason) {
        assertCanDecide(user(userId), environmentId);
        Environment environment = environment(environmentId);
        if (environment.getStartFrozenUntil() == null || !environment.getStartFrozenUntil().toInstant().isAfter(clock.instant())) {
            throw new ValidationException("Starts are not frozen in this environment");
        }
        environment.setStartFrozenUntil(null);
        environmentRepository.save(environment);
        auditService.logEnvironmentAction(userId, AuditAction.BUDGET_FREEZE_LIFTED, environmentId, name(environment),
                "environment", environmentId, name(environment), "Lifted the budget start freeze" + reasonSuffix(reason));
    }

    // ============= Helpers =============

    private boolean canDecide(User user, String environmentId) {
        return user.isAdmin() || securityService.effectiveEnvLevel(user, environmentId) == AccessLevel.ADMIN;
    }

    private void assertCanDecide(User user, String environmentId) {
        if (!canDecide(user, environmentId)) {
            throw new UnauthorizedException("Only the environment's owners can decide its budget actions");
        }
    }

    private BudgetActionRequest pending(String requestId) {
        BudgetActionRequest request = requestRepository.findByIdFetchBudget(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("BudgetActionRequest", requestId));
        if (request.getStatus() != BudgetActionRequest.Status.PENDING) {
            throw new ValidationException("This budget action was already " + request.getStatus().name().toLowerCase());
        }
        return request;
    }

    private void decide(BudgetActionRequest request, BudgetActionRequest.Status status, String userId, String reason) {
        request.setStatus(status);
        request.setDecidedByUserId(userId);
        request.setDecidedAt(Timestamp.from(clock.instant()));
        request.setDecisionReason(reason == null || reason.isBlank() ? null
                : reason.trim().substring(0, Math.min(reason.trim().length(), MAX_REASON)));
        requestRepository.save(request);
    }

    private User user(String userId) {
        return userRepository.findById(userId).orElseThrow(() -> new UnauthorizedException("Unknown user"));
    }

    private Environment environment(String environmentId) {
        return environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
    }

    private static String name(Environment environment) {
        return environment.getDisplayName() != null ? environment.getDisplayName() : environment.getName();
    }

    static String describe(BudgetActionType action) {
        return action == BudgetActionType.STOP_ENVIRONMENT ? "stop the environment" : "freeze starts";
    }

    private static String reasonSuffix(String reason) {
        return reason == null || reason.isBlank() ? "" : ": " + reason.trim();
    }

    BudgetActionRequestDTO toDto(BudgetActionRequest r) {
        Budget b = r.getBudget();
        Optional<Environment> environment = environmentRepository.findById(b.getScopeId());
        String decidedBy = r.getDecidedByUserId() == null ? null : userRepository.findById(r.getDecidedByUserId())
                .map(u -> u.getDisplayName() != null ? u.getDisplayName() : u.getEmail()).orElse(r.getDecidedByUserId());
        return new BudgetActionRequestDTO(r.getRequestId(), b.getBudgetId(), b.getScopeId(),
                environment.map(BudgetActionService::name).orElse(b.getScopeId()), r.getBudgetMonth(), r.getActionType(),
                r.getStatus(), b.getMonthlyAmount(), r.getRequestedAt(), r.getDecidedByUserId(), decidedBy, r.getDecidedAt(),
                r.getDecisionReason(), r.getExecutionId());
    }
}
