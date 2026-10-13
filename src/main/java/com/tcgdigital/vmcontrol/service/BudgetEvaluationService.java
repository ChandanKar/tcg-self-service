package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Budget;
import com.tcgdigital.vmcontrol.model.BudgetAlert;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;
import com.tcgdigital.vmcontrol.repository.BudgetAlertRepository;
import com.tcgdigital.vmcontrol.repository.BudgetRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Daily budget evaluation (E20-T02): each enabled budget alerts its owners once per month at
 * each threshold its spend reaches, and once when the month-end forecast exceeds the budget.
 *
 * <p>The month evaluated is that of yesterday (the last complete day), so the 1st still checks
 * the previous month's final day. An alert row is written before anyone is told; its unique key
 * (budget, month, threshold, kind) makes a re-run send nothing twice, and a new month starts
 * afresh. One budget's or one notification's failure never stops the others.
 */
@Service
public class BudgetEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(BudgetEvaluationService.class);

    /** Platform events for webhooks (E34-T07); absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.events.PlatformEvents platformEvents;

    private final BudgetRepository budgetRepository;
    private final BudgetAlertRepository alertRepository;
    private final BudgetService budgetService;
    private final NotificationService notificationService;
    private final EnvironmentRepository environmentRepository;
    private final BudgetActionService actionService;

    /** Exhausted budgets request their action (E20-T03); off by default, as it changes what users can do. */
    @org.springframework.beans.factory.annotation.Value("${budgets.actions.enabled:false}")
    private boolean actionsEnabled = false;

    /** Replaceable in tests. */
    private Clock clock = Clock.systemUTC();

    public BudgetEvaluationService(BudgetRepository budgetRepository, BudgetAlertRepository alertRepository,
                                   BudgetService budgetService, NotificationService notificationService,
                                   EnvironmentRepository environmentRepository, BudgetActionService actionService) {
        this.actionService = actionService;
        this.budgetRepository = budgetRepository;
        this.alertRepository = alertRepository;
        this.budgetService = budgetService;
        this.notificationService = notificationService;
        this.environmentRepository = environmentRepository;
    }

    /** How many alerts fired. */
    public int evaluateAll() {
        LocalDate asOf = LocalDate.now(clock).minusDays(1);
        int fired = 0;
        try {
            actionService.expireBefore(YearMonth.from(asOf));
            actionService.retryPendingStops(); // approved stops that were blocked by a lock (E20-T04)
        } catch (Exception e) {
            log.error("Could not expire or retry budget action requests: {}", e.getMessage(), e);
        }
        for (Budget budget : budgetRepository.findByEnabledTrue()) {
            try {
                fired += evaluate(budget, YearMonth.from(asOf), asOf);
            } catch (Exception e) {
                log.error("Budget {} could not be evaluated: {}", budget.getBudgetId(), e.getMessage(), e);
            }
        }
        if (fired > 0) {
            log.info("Budget evaluation fired {} alert(s)", fired);
        }
        return fired;
    }

    int evaluate(Budget budget, YearMonth month, LocalDate asOf) {
        BudgetService.Spend spend = budgetService.spend(budget, month, asOf);
        BigDecimal amount = budget.getMonthlyAmount();
        String scopeName = scopeName(budget);
        List<String> environmentIds = budgetService.environmentIds(budget);
        int fired = 0;
        for (int threshold : budget.thresholdList()) {
            BigDecimal limit = amount.multiply(BigDecimal.valueOf(threshold)).divide(BigDecimal.valueOf(100));
            if (spend.toDate().compareTo(limit) >= 0 && record(budget, month, threshold, BudgetAlert.Kind.ACTUAL, spend)) {
                fired++;
                notify(() -> notificationService.notifyBudgetThreshold(environmentIds, budget.getBudgetId(),
                        budget.getScopeType() == BudgetScopeType.TEAM, scopeName, month, threshold, spend.toDate(), amount));
                if (platformEvents != null) {
                    java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
                    data.put("budgetId", budget.getBudgetId());
                    data.put("scope", scopeName);
                    data.put("month", String.valueOf(month));
                    data.put("thresholdPercent", threshold);
                    data.put("spend", spend.toDate());
                    data.put("amount", amount);
                    for (String environmentId : environmentIds) {
                        platformEvents.publish(com.tcgdigital.vmcontrol.service.events.PlatformEventType.BUDGET_THRESHOLD, environmentId, null, data);
                    }
                }
            }
        }
        if (actionsEnabled && budget.getActionType() != com.tcgdigital.vmcontrol.model.BudgetActionType.NONE
                && spend.toDate().compareTo(amount) >= 0) {
            try {
                actionService.raise(budget, month);
            } catch (Exception e) {
                log.error("Could not raise the action of budget {}: {}", budget.getBudgetId(), e.getMessage(), e);
            }
        }
        if (Boolean.TRUE.equals(budget.getForecastAlert()) && spend.forecast() != null
                && spend.forecast().compareTo(amount) > 0
                && record(budget, month, 100, BudgetAlert.Kind.FORECAST, spend)) {
            fired++;
            notify(() -> notificationService.notifyBudgetForecast(environmentIds, budget.getBudgetId(),
                    budget.getScopeType() == BudgetScopeType.TEAM, scopeName, month, spend.forecast(), amount));
        }
        return fired;
    }

    /** True when this alert had not fired yet (and now has). */
    private boolean record(Budget budget, YearMonth month, int threshold, BudgetAlert.Kind kind, BudgetService.Spend spend) {
        if (alertRepository.existsByBudgetBudgetIdAndBudgetMonthAndThresholdAndKind(budget.getBudgetId(), month.toString(),
                threshold, kind)) {
            return false;
        }
        BudgetAlert alert = new BudgetAlert();
        alert.setAlertId(UUID.randomUUID().toString());
        alert.setBudget(budget);
        alert.setBudgetMonth(month.toString());
        alert.setThreshold(threshold);
        alert.setKind(kind);
        alert.setSpend(spend.toDate());
        alert.setForecast(spend.forecast());
        try {
            alertRepository.saveAndFlush(alert);
            return true;
        } catch (DataIntegrityViolationException e) {
            return false; // a concurrent run fired it first
        }
    }

    private static void notify(Runnable send) {
        try {
            send.run();
        } catch (Exception e) {
            log.warn("Budget alert notification failed: {}", e.getMessage(), e);
        }
    }

    private String scopeName(Budget budget) {
        if (budget.getScopeType() == BudgetScopeType.TEAM) {
            return "Team " + budget.getScopeId();
        }
        return environmentRepository.findById(budget.getScopeId())
                .map(e -> e.getDisplayName() != null ? e.getDisplayName() : e.getName())
                .orElse(budget.getScopeId());
    }
}
