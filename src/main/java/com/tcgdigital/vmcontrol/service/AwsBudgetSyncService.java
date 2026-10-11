package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import com.tcgdigital.vmcontrol.model.Budget;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.budgets.BudgetsClient;
import software.amazon.awssdk.services.budgets.model.BudgetType;
import software.amazon.awssdk.services.budgets.model.CreateBudgetRequest;
import software.amazon.awssdk.services.budgets.model.DeleteBudgetRequest;
import software.amazon.awssdk.services.budgets.model.UpdateBudgetRequest;
import software.amazon.awssdk.services.budgets.model.DuplicateRecordException;
import software.amazon.awssdk.services.budgets.model.NotFoundException;
import software.amazon.awssdk.services.budgets.model.Spend;
import software.amazon.awssdk.services.budgets.model.TimeUnit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional mirror of app budgets into AWS Budgets (E20-T06), for AWS-native alerting even when
 * the app is down. Off by default (budgets.aws-sync.enabled) because it changes AWS state.
 *
 * <p>Each app budget becomes a monthly COST budget in USD filtered on its cost-allocation tag
 * ({@code user:tcg:environment$<name>} or {@code user:tcg:team$<team>}); AWS-side notifications
 * are not created, the app routes its own alerts. A failed call is logged and remembered per
 * budget (in memory) so the budget shows "AWS sync failed"; the app budget is saved regardless.
 */
@Service
public class AwsBudgetSyncService {

    private static final Logger log = LoggerFactory.getLogger(AwsBudgetSyncService.class);
    static final int MAX_NAME = 100;

    private final boolean enabled;
    private final String accountId;
    private final String tagKeyPrefix;
    private final AwsClientFactory awsClientFactory;
    private volatile BudgetsClient client;
    private final Map<String, String> lastErrors = new ConcurrentHashMap<>();

    @Autowired
    public AwsBudgetSyncService(@Value("${budgets.aws-sync.enabled:false}") boolean enabled,
                                @Value("${budgets.aws-sync.account-id:}") String accountId,
                                @Value("${cost.tagging.key-prefix:tcg:}") String tagKeyPrefix,
                                AwsClientFactory awsClientFactory) {
        this.enabled = enabled;
        this.accountId = accountId;
        this.tagKeyPrefix = tagKeyPrefix;
        this.awsClientFactory = awsClientFactory;
    }

    /** With a ready (mock) client (tests). */
    AwsBudgetSyncService(boolean enabled, String accountId, BudgetsClient client) {
        this(enabled, accountId, "tcg:", null);
        this.client = client;
    }

    public boolean isEnabled() {
        return enabled && accountId != null && !accountId.isBlank();
    }

    /** The "AWS sync failed" message of a budget's last sync, if it failed. */
    public Optional<String> lastError(String budgetId) {
        return Optional.ofNullable(lastErrors.get(budgetId));
    }

    /** The AWS budget's name for an app budget. */
    static String awsName(Budget budget) {
        String name = "tcg-" + budget.getScopeType().name().toLowerCase() + "-" + budget.getScopeId();
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    /** The cost filter value, e.g. "user:tcg:environment$prod-01" ({@code tagValue} = environment name or team). */
    String tagFilter(Budget budget, String tagValue) {
        String key = budget.getScopeType() == BudgetScopeType.TEAM ? "team" : "environment";
        return "user:" + tagKeyPrefix + key + "$" + tagValue;
    }

    /**
     * Creates or updates the AWS budget; returns its name, or null when disabled or failed (the
     * failure is remembered). {@code tagValue} is the environment's name or the team.
     */
    public String upsert(Budget budget, String tagValue) {
        if (!isEnabled()) {
            return null;
        }
        String name = budget.getAwsBudgetName() != null ? budget.getAwsBudgetName() : awsName(budget);
        software.amazon.awssdk.services.budgets.model.Budget aws = toAwsBudget(name, budget.getMonthlyAmount(),
                tagFilter(budget, tagValue));
        try {
            if (budget.getAwsBudgetName() == null) {
                try {
                    client().createBudget(CreateBudgetRequest.builder().accountId(accountId).budget(aws).build());
                } catch (DuplicateRecordException e) {
                    client().updateBudget(UpdateBudgetRequest.builder().accountId(accountId).newBudget(aws).build());
                }
            } else {
                try {
                    client().updateBudget(UpdateBudgetRequest.builder().accountId(accountId).newBudget(aws).build());
                } catch (NotFoundException e) {
                    client().createBudget(CreateBudgetRequest.builder().accountId(accountId).budget(aws).build());
                }
            }
            lastErrors.remove(budget.getBudgetId());
            return name;
        } catch (Exception e) {
            log.warn("AWS Budgets sync of budget {} failed: {}", budget.getBudgetId(), e.getMessage());
            lastErrors.put(budget.getBudgetId(), "AWS sync failed: " + e.getMessage());
            return null;
        }
    }

    /** Deletes the AWS budget of a deleted app budget (a missing one is fine). */
    public void delete(String budgetId, String awsBudgetName) {
        lastErrors.remove(budgetId);
        if (!isEnabled() || awsBudgetName == null) {
            return;
        }
        try {
            client().deleteBudget(DeleteBudgetRequest.builder().accountId(accountId).budgetName(awsBudgetName).build());
        } catch (NotFoundException e) {
            // already gone
        } catch (Exception e) {
            log.warn("AWS Budgets delete of {} failed: {}", awsBudgetName, e.getMessage());
        }
    }

    static software.amazon.awssdk.services.budgets.model.Budget toAwsBudget(String name, BigDecimal amount, String tagFilter) {
        return software.amazon.awssdk.services.budgets.model.Budget.builder()
                .budgetName(name)
                .budgetType(BudgetType.COST)
                .timeUnit(TimeUnit.MONTHLY)
                .budgetLimit(Spend.builder().amount(amount).unit("USD").build())
                .costFilters(Map.of("TagKeyValue", List.of(tagFilter)))
                .build();
    }

    private BudgetsClient client() {
        BudgetsClient existing = client;
        if (existing != null) {
            return existing;
        }
        // AWS Budgets has one global endpoint, in us-east-1; the factory caches it (E28-T02).
        return awsClientFactory.budgets();
    }
}
