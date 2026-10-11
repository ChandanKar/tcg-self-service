package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.BudgetDTO;
import com.tcgdigital.vmcontrol.dto.BudgetRequestDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Budget;
import com.tcgdigital.vmcontrol.model.BudgetActionType;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.BudgetRepository;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Monthly budgets per environment or team (E20-T01): CRUD with authorization, and the month's
 * spend (actual where AWS billing has it, else the estimate, over complete days) and forecast.
 *
 * <p>Admins manage any budget; an environment's budget is also managed by whoever has an
 * effective ADMIN level on it; team budgets are admin only. Anyone who can view an environment
 * can read its budget.
 */
@Service
public class BudgetService {

    /** Days averaged for the run-rate forecast (as the owner cost view, E18-T01). */
    static final int RUN_RATE_DAYS = 7;
    static final List<Integer> DEFAULT_THRESHOLDS = List.of(50, 75, 90, 100);

    private final BudgetRepository budgetRepository;
    private final EnvironmentRepository environmentRepository;
    private final CostDailySnapshotRepository snapshotRepository;
    private final TeamResolver teamResolver;
    private final SecurityService securityService;
    private final AuditService auditService;

    /** Replaceable in tests. */
    private Clock clock = Clock.systemUTC();

    public BudgetService(BudgetRepository budgetRepository, EnvironmentRepository environmentRepository,
                         CostDailySnapshotRepository snapshotRepository, TeamResolver teamResolver,
                         SecurityService securityService, AuditService auditService) {
        this.budgetRepository = budgetRepository;
        this.environmentRepository = environmentRepository;
        this.snapshotRepository = snapshotRepository;
        this.teamResolver = teamResolver;
        this.securityService = securityService;
        this.auditService = auditService;
    }

    // ============= Reads =============

    /** Admins see every budget; others the budgets of environments they can view. */
    @Transactional(readOnly = true)
    public List<BudgetDTO> list() {
        boolean admin = securityService.isAdmin();
        return budgetRepository.findAllByOrderByScopeTypeAscScopeIdAsc().stream()
                .filter(b -> admin || (b.getScopeType() == BudgetScopeType.ENVIRONMENT
                        && securityService.canViewEnvironment(b.getScopeId())))
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public BudgetDTO get(String budgetId) {
        Budget budget = budget(budgetId);
        assertCanRead(budget);
        return toDto(budget);
    }

    /** The environment's budget, or empty when it has none. */
    @Transactional(readOnly = true)
    public Optional<BudgetDTO> forEnvironment(String environmentId) {
        securityService.assertCanView(environmentId);
        return budgetRepository.findByScopeTypeAndScopeId(BudgetScopeType.ENVIRONMENT, environmentId).map(this::toDto);
    }

    /** The teams environments belong to, for the budget form (admins). */
    @Transactional(readOnly = true)
    public List<String> teams() {
        return environmentRepository.findByIsActiveTrue().stream()
                .map(e -> teamResolver.resolveTeam(e.getMetadata())).distinct().sorted().toList();
    }

    // ============= Writes =============

    @Transactional
    public BudgetDTO create(String userId, BudgetRequestDTO request) {
        String scopeId = request.scopeId().trim();
        assertCanManage(request.scopeType(), scopeId);
        String scopeName = scopeName(request.scopeType(), scopeId);
        if (budgetRepository.findByScopeTypeAndScopeId(request.scopeType(), scopeId).isPresent()) {
            throw new ValidationException(request.scopeType() == BudgetScopeType.ENVIRONMENT
                    ? "This environment already has a budget" : "Team " + scopeId + " already has a budget");
        }
        Budget budget = new Budget();
        budget.setBudgetId(UUID.randomUUID().toString());
        budget.setScopeType(request.scopeType());
        budget.setScopeId(scopeId);
        budget.setCreatedByUserId(userId);
        apply(budget, request);
        budget = budgetRepository.save(budget);
        audit(userId, AuditAction.BUDGET_CREATED, budget, scopeName, describe(budget));
        return toDto(budget);
    }

    @Transactional
    public BudgetDTO update(String budgetId, String userId, BudgetRequestDTO request) {
        Budget budget = budget(budgetId);
        assertCanManage(budget.getScopeType(), budget.getScopeId());
        if (request.scopeType() != budget.getScopeType() || !budget.getScopeId().equals(request.scopeId().trim())) {
            throw new ValidationException("The scope of a budget cannot be changed");
        }
        apply(budget, request);
        budget = budgetRepository.save(budget);
        audit(userId, AuditAction.BUDGET_UPDATED, budget, scopeName(budget.getScopeType(), budget.getScopeId()), describe(budget));
        return toDto(budget);
    }

    @Transactional
    public void delete(String budgetId, String userId) {
        Budget budget = budget(budgetId);
        assertCanManage(budget.getScopeType(), budget.getScopeId());
        budgetRepository.delete(budget);
        audit(userId, AuditAction.BUDGET_DELETED, budget, scopeName(budget.getScopeType(), budget.getScopeId()), describe(budget));
    }

    private void apply(Budget budget, BudgetRequestDTO request) {
        BudgetActionType action = request.actionType() == null ? BudgetActionType.NONE : request.actionType();
        if (action != BudgetActionType.NONE && request.scopeType() != BudgetScopeType.ENVIRONMENT) {
            throw new ValidationException("Freeze and stop actions are only available for environment budgets");
        }
        budget.setMonthlyAmount(request.monthlyAmount().setScale(2, RoundingMode.HALF_UP));
        budget.setThresholdList(validThresholds(request.thresholds()));
        budget.setForecastAlert(request.forecastAlert() == null || request.forecastAlert());
        budget.setActionType(action);
        budget.setEnabled(request.enabled() == null || request.enabled());
    }

    /** 1 to 4 different whole percentages between 1 and 200; null means the defaults. */
    static List<Integer> validThresholds(List<Integer> thresholds) {
        if (thresholds == null) {
            return DEFAULT_THRESHOLDS;
        }
        boolean valid = !thresholds.isEmpty() && thresholds.size() <= 4
                && thresholds.stream().allMatch(t -> t != null && t >= 1 && t <= 200)
                && new HashSet<>(thresholds).size() == thresholds.size();
        if (!valid) {
            throw new ValidationException("Thresholds must be 1 to 4 different whole percentages between 1 and 200");
        }
        return thresholds.stream().sorted().toList();
    }

    // ============= Authorization =============

    private void assertCanManage(BudgetScopeType scopeType, String scopeId) {
        if (securityService.isAdmin()) {
            return;
        }
        if (scopeType == BudgetScopeType.TEAM) {
            throw new UnauthorizedException("Only an admin can manage team budgets");
        }
        if (securityService.effectiveEnvLevel(scopeId) != AccessLevel.ADMIN) {
            throw new UnauthorizedException("Only the environment's owners can manage its budget");
        }
    }

    private void assertCanRead(Budget budget) {
        if (securityService.isAdmin()) {
            return;
        }
        if (budget.getScopeType() == BudgetScopeType.TEAM) {
            throw new UnauthorizedException("Only an admin can see team budgets");
        }
        securityService.assertCanView(budget.getScopeId());
    }

    // ============= Spend =============

    /** Spend to date over complete days up to {@code asOf}, and the month-end forecast (null without data). */
    public record Spend(YearMonth month, LocalDate asOf, BigDecimal toDate, BigDecimal forecast) {}

    /** This month's spend as of yesterday (today is not complete yet). */
    @Transactional(readOnly = true)
    public Spend currentSpend(Budget budget) {
        LocalDate today = LocalDate.now(clock);
        return spend(budget, YearMonth.from(today), today.minusDays(1));
    }

    /**
     * Per day, the actual cost when AWS billing has it, else the estimate, summed over the
     * budget's environments for the month's days up to {@code asOf}; the forecast adds the average
     * of the last {@value #RUN_RATE_DAYS} days for each day left in the month.
     */
    @Transactional(readOnly = true)
    public Spend spend(Budget budget, YearMonth month, LocalDate asOf) {
        LocalDate monthStart = month.atDay(1);
        LocalDate monthEnd = month.atEndOfMonth();
        LocalDate until = asOf.isAfter(monthEnd) ? monthEnd : asOf;
        List<String> environmentIds = environmentIds(budget);
        Map<LocalDate, BigDecimal> perDay = new TreeMap<>();
        LocalDate from = until.minusDays(RUN_RATE_DAYS - 1L).isBefore(monthStart) ? until.minusDays(RUN_RATE_DAYS - 1L) : monthStart;
        if (!environmentIds.isEmpty() && !until.isBefore(from)) {
            snapshotRepository.findDailyWithActualByEnvironmentIdsBetween(environmentIds, Date.valueOf(from), Date.valueOf(until))
                    .forEach(row -> perDay.merge(row.getSnapshotDate().toLocalDate(),
                            row.getActualCost() != null ? row.getActualCost() : nz(row.getEstimatedCost()), BigDecimal::add));
        }
        BigDecimal toDate = perDay.entrySet().stream()
                .filter(e -> !e.getKey().isBefore(monthStart) && !e.getKey().isAfter(until))
                .map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BigDecimal> recent = perDay.entrySet().stream()
                .filter(e -> !e.getKey().isBefore(until.minusDays(RUN_RATE_DAYS - 1L)))
                .map(Map.Entry::getValue).toList();
        int daysRemaining = monthEnd.getDayOfMonth() - (until.isBefore(monthStart) ? 0 : until.getDayOfMonth());
        BigDecimal forecast = CostRunRate.forecast(toDate, recent, daysRemaining);
        return new Spend(month, until, money(toDate), forecast == null ? null : money(forecast));
    }

    /** The environments a budget covers: its environment, or the active environments of its team. */
    public List<String> environmentIds(Budget budget) {
        if (budget.getScopeType() == BudgetScopeType.ENVIRONMENT) {
            return List.of(budget.getScopeId());
        }
        List<String> ids = new ArrayList<>();
        for (Environment e : environmentRepository.findByIsActiveTrue()) {
            if (budget.getScopeId().equals(teamResolver.resolveTeam(e.getMetadata()))) {
                ids.add(e.getEnvironmentId());
            }
        }
        return ids;
    }

    // ============= Helpers =============

    private Budget budget(String budgetId) {
        return budgetRepository.findById(budgetId).orElseThrow(() -> new ResourceNotFoundException("Budget", budgetId));
    }

    /** The environment's name (it must exist) or the team name. */
    private String scopeName(BudgetScopeType scopeType, String scopeId) {
        if (scopeType == BudgetScopeType.TEAM) {
            return scopeId;
        }
        Environment environment = environmentRepository.findById(scopeId)
                .orElseThrow(() -> new ValidationException("Environment not found: " + scopeId));
        return environment.getDisplayName() != null ? environment.getDisplayName() : environment.getName();
    }

    BudgetDTO toDto(Budget budget) {
        Spend spend = currentSpend(budget);
        String name = budget.getScopeId();
        Timestamp frozenUntil = null;
        if (budget.getScopeType() == BudgetScopeType.TEAM) {
            name = budget.getScopeId();
        } else {
            Optional<Environment> environment = environmentRepository.findById(budget.getScopeId());
            if (environment.isPresent()) {
                Environment e = environment.get();
                name = e.getDisplayName() != null ? e.getDisplayName() : e.getName();
                Timestamp until = e.getStartFrozenUntil();
                frozenUntil = until != null && until.toInstant().isAfter(clock.instant()) ? until : null;
            }
        }
        return new BudgetDTO(budget.getBudgetId(), budget.getScopeType(), budget.getScopeId(), name,
                budget.getMonthlyAmount(), budget.thresholdList(), Boolean.TRUE.equals(budget.getForecastAlert()),
                budget.getActionType(), Boolean.TRUE.equals(budget.getEnabled()), budget.getAwsBudgetName(),
                spend.month().toString(), spend.asOf(), spend.toDate(), percent(spend.toDate(), budget.getMonthlyAmount()),
                spend.forecast(), spend.forecast() == null ? null : percent(spend.forecast(), budget.getMonthlyAmount()),
                budget.getCreatedByUserId(), budget.getCreatedAt(), budget.getUpdatedAt(), frozenUntil);
    }

    private void audit(String userId, AuditAction action, Budget budget, String scopeName, String details) {
        boolean environment = budget.getScopeType() == BudgetScopeType.ENVIRONMENT;
        auditService.logEnvironmentAction(userId, action, environment ? budget.getScopeId() : null,
                environment ? scopeName : null, "budget", budget.getBudgetId(),
                (environment ? "" : "Team ") + scopeName, details);
    }

    private static String describe(Budget budget) {
        return "$" + budget.getMonthlyAmount().toPlainString() + "/month, alerts at " + budget.getThresholds() + "%"
                + (Boolean.TRUE.equals(budget.getForecastAlert()) ? " and on forecast" : "")
                + ", action " + budget.getActionType() + (Boolean.TRUE.equals(budget.getEnabled()) ? "" : " (disabled)");
    }

    static BigDecimal percent(BigDecimal value, BigDecimal amount) {
        if (value == null || amount == null || amount.signum() == 0) {
            return null;
        }
        return value.multiply(BigDecimal.valueOf(100)).divide(amount, 1, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
