package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.AttentionSummaryDTO;
import com.tcgdigital.vmcontrol.dto.BudgetDTO;
import com.tcgdigital.vmcontrol.dto.DashboardSummaryDTO;
import com.tcgdigital.vmcontrol.model.AccessRequestStatus;
import com.tcgdigital.vmcontrol.model.ApprovalRequest;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.repository.ApprovalRequestRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRequestRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the administrator's "needs attention" summary (U05-T04) from the places that already know
 * each fact: access and approval requests, operation runs, the VM registry's review flags, budgets,
 * the scheduled-job catalog and the state sync.
 */
@Service
public class AttentionSummaryService {

    private static final BigDecimal NEAR_LIMIT_PERCENT = BigDecimal.valueOf(90);
    private static final BigDecimal OVER_PERCENT = BigDecimal.valueOf(100);
    private static final Set<String> LATE = Set.of("LATE", "STALE", "NEVER");
    private static final Set<String> ON_TIME = Set.of("HEALTHY", "RUNNING");

    private final EnvironmentAccessRequestRepository accessRequests;
    private final ApprovalRequestRepository approvals;
    private final OperationExecutionRepository executions;
    private final VmRepository vms;
    private final VmStateHistoryRepository history;
    private final EnvironmentRepository environments;
    private final BudgetService budgetService;
    private final DashboardSummaryService dashboardSummaryService;
    private final StateSyncService stateSyncService;
    private final long staleAfterMinutes;
    private final boolean costSnapshotEnabled;
    private Clock clock = Clock.systemUTC();

    public AttentionSummaryService(EnvironmentAccessRequestRepository accessRequests,
                                   ApprovalRequestRepository approvals,
                                   OperationExecutionRepository executions,
                                   VmRepository vms,
                                   VmStateHistoryRepository history,
                                   EnvironmentRepository environments,
                                   BudgetService budgetService,
                                   DashboardSummaryService dashboardSummaryService,
                                   StateSyncService stateSyncService,
                                   @Value("${vm.operations.stale-after-minutes:30}") long staleAfterMinutes,
                                   @Value("${cost.snapshot.enabled:true}") boolean costSnapshotEnabled) {
        this.accessRequests = accessRequests;
        this.approvals = approvals;
        this.executions = executions;
        this.vms = vms;
        this.history = history;
        this.environments = environments;
        this.budgetService = budgetService;
        this.dashboardSummaryService = dashboardSummaryService;
        this.stateSyncService = stateSyncService;
        this.staleAfterMinutes = staleAfterMinutes;
        this.costSnapshotEnabled = costSnapshotEnabled;
    }

    /** For tests: a fixed "now". */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AttentionSummaryDTO summary() {
        Instant now = clock.instant();
        Timestamp dayAgo = Timestamp.from(now.minus(Duration.ofHours(24)));
        List<BudgetDTO> budgets = budgetService.list();
        List<DashboardSummaryDTO.SchedulerHealthDTO> jobs = dashboardSummaryService.buildSchedulerHealth(true);
        return new AttentionSummaryDTO(
                accessRequestsWaiting(),
                approvalsWaiting(),
                failedOperations(dayAgo, now),
                new AttentionSummaryDTO.VmsToReview(vms.countByIsActiveTrueAndStateDriftDetectedTrue(),
                        vms.countByIsActiveTrueAndDiscoveryPendingTrue()),
                budgetsNearLimit(budgets),
                lateJobs(jobs),
                health(jobs, dayAgo, now),
                monthPlan(budgets),
                Timestamp.from(now));
    }

    private AttentionSummaryDTO.Waiting accessRequestsWaiting() {
        long count = accessRequests.countByStatus(AccessRequestStatus.PENDING);
        Timestamp oldest = count == 0 ? null : accessRequests.findTopByStatusOrderByCreatedAtAsc(AccessRequestStatus.PENDING)
                .map(EnvironmentAccessRequest::getCreatedAt).orElse(null);
        return new AttentionSummaryDTO.Waiting(count, oldest);
    }

    private AttentionSummaryDTO.Waiting approvalsWaiting() {
        long count = approvals.countByStatus(ApprovalRequest.Status.PENDING);
        Timestamp oldest = count == 0 ? null : approvals.findTopByStatusOrderByCreatedAtAsc(ApprovalRequest.Status.PENDING)
                .map(ApprovalRequest::getCreatedAt).orElse(null);
        return new AttentionSummaryDTO.Waiting(count, oldest);
    }

    private AttentionSummaryDTO.FailedOperations failedOperations(Timestamp since, Instant now) {
        List<OperationExecution> failed = executions.findFailedSinceWithEnvironment(since);
        Set<String> names = new LinkedHashSet<>();
        for (OperationExecution run : failed) {
            Environment env = run.getEnvironment();
            if (env != null) {
                names.add(env.getDisplayName() != null && !env.getDisplayName().isBlank() ? env.getDisplayName() : env.getName());
            }
            if (names.size() == 3) break;
        }
        long stuck = executions.countStuckBefore(Timestamp.from(now.minus(Duration.ofMinutes(staleAfterMinutes))));
        return new AttentionSummaryDTO.FailedOperations(failed.size(), stuck, List.copyOf(names));
    }

    private AttentionSummaryDTO.Budgets budgetsNearLimit(List<BudgetDTO> budgets) {
        if (!costSnapshotEnabled) {
            return new AttentionSummaryDTO.Budgets(0, null);
        }
        List<BudgetDTO> near = budgets.stream()
                .filter(BudgetDTO::enabled)
                .filter(b -> atLeast(b.percentUsed(), NEAR_LIMIT_PERCENT) || atLeast(b.forecastPercent(), OVER_PERCENT))
                .toList();
        AttentionSummaryDTO.BudgetUse top = near.stream()
                .max(Comparator.comparing(b -> b.percentUsed() == null ? BigDecimal.ZERO : b.percentUsed()))
                .map(b -> new AttentionSummaryDTO.BudgetUse(b.scopeName(), b.percentUsed()))
                .orElse(null);
        return new AttentionSummaryDTO.Budgets(near.size(), top);
    }

    private static boolean atLeast(BigDecimal value, BigDecimal limit) {
        return value != null && value.compareTo(limit) >= 0;
    }

    private AttentionSummaryDTO.LateJobs lateJobs(List<DashboardSummaryDTO.SchedulerHealthDTO> jobs) {
        List<DashboardSummaryDTO.SchedulerHealthDTO> late = jobs.stream()
                .filter(job -> LATE.contains(job.status()))
                .toList();
        AttentionSummaryDTO.LateJob worst = late.stream()
                .max(Comparator.comparing(AttentionSummaryService::lateBy, Comparator.nullsFirst(Long::compareTo)))
                .map(job -> new AttentionSummaryDTO.LateJob(job.name(), lateBy(job)))
                .orElse(null);
        return new AttentionSummaryDTO.LateJobs(late.size(), worst);
    }

    /** How far past its interval a job is; null when it never ran (no freshness to compare). */
    private static Long lateBy(DashboardSummaryDTO.SchedulerHealthDTO job) {
        if (job.freshnessSeconds() == null || job.expectedIntervalSeconds() == null) return null;
        return Math.max(0, job.freshnessSeconds() - job.expectedIntervalSeconds());
    }

    private AttentionSummaryDTO.Health health(List<DashboardSummaryDTO.SchedulerHealthDTO> jobs, Timestamp dayAgo, Instant now) {
        long enabled = jobs.stream().filter(job -> !"DISABLED".equals(job.status())).count();
        long onTime = jobs.stream().filter(job -> ON_TIME.contains(job.status())).count();
        Timestamp lastSync = stateSyncService.getSyncStatus().getLastSyncTime();
        long drift = history.countDriftEventsInRange(dayAgo, Timestamp.from(now));
        return new AttentionSummaryDTO.Health(enabled, onTime, lastSync, drift);
    }

    /**
     * The month's plan: enabled team budgets, plus enabled environment budgets of environments that
     * no enabled team budget already covers. Null when no budget is enabled.
     */
    private AttentionSummaryDTO.MonthPlan monthPlan(List<BudgetDTO> budgets) {
        List<BudgetDTO> enabled = budgets.stream().filter(BudgetDTO::enabled).toList();
        if (enabled.isEmpty()) {
            return null;
        }
        Set<String> teamsWithBudget = enabled.stream()
                .filter(b -> b.scopeType() == BudgetScopeType.TEAM)
                .map(BudgetDTO::scopeId)
                .collect(Collectors.toSet());
        List<String> envIds = enabled.stream()
                .filter(b -> b.scopeType() == BudgetScopeType.ENVIRONMENT)
                .map(BudgetDTO::scopeId)
                .toList();
        Map<String, Environment> envById = envIds.isEmpty() ? Map.of()
                : environments.findAllById(envIds).stream().collect(Collectors.toMap(Environment::getEnvironmentId, Function.identity()));

        BigDecimal amount = BigDecimal.ZERO;
        long count = 0;
        for (BudgetDTO budget : enabled) {
            if (budget.scopeType() == BudgetScopeType.ENVIRONMENT) {
                Environment env = envById.get(budget.scopeId());
                if (env != null && env.getTeamId() != null && teamsWithBudget.contains(env.getTeamId())) {
                    continue; // already inside its team's budget
                }
            }
            amount = amount.add(budget.monthlyAmount() == null ? BigDecimal.ZERO : budget.monthlyAmount());
            count++;
        }
        return new AttentionSummaryDTO.MonthPlan(amount, count);
    }
}
