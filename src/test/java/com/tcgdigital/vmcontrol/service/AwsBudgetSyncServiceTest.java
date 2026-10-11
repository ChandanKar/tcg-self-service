package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Budget;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.budgets.BudgetsClient;
import software.amazon.awssdk.services.budgets.model.BudgetType;
import software.amazon.awssdk.services.budgets.model.BudgetsException;
import software.amazon.awssdk.services.budgets.model.CreateBudgetRequest;
import software.amazon.awssdk.services.budgets.model.DeleteBudgetRequest;
import software.amazon.awssdk.services.budgets.model.NotFoundException;
import software.amazon.awssdk.services.budgets.model.TimeUnit;
import software.amazon.awssdk.services.budgets.model.UpdateBudgetRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Mirroring budgets to AWS Budgets (E20-T06): request mapping, update vs create, failures, and the off switch. */
class AwsBudgetSyncServiceTest {

    private final BudgetsClient client = mock(BudgetsClient.class);

    private static Budget budget(BudgetScopeType type, String scopeId, String awsName) {
        Budget b = new Budget();
        b.setBudgetId("b-1");
        b.setScopeType(type);
        b.setScopeId(scopeId);
        b.setMonthlyAmount(new BigDecimal("500.00"));
        b.setAwsBudgetName(awsName);
        return b;
    }

    @Test
    void aNewEnvironmentBudgetCreatesAMonthlyCostBudgetOnItsTag() {
        AwsBudgetSyncService sync = new AwsBudgetSyncService(true, "123456789012", client);

        String name = sync.upsert(budget(BudgetScopeType.ENVIRONMENT, "env-1", null), "prod-01");

        ArgumentCaptor<CreateBudgetRequest> sent = ArgumentCaptor.forClass(CreateBudgetRequest.class);
        verify(client).createBudget(sent.capture());
        assertThat(sent.getValue().accountId()).isEqualTo("123456789012");
        software.amazon.awssdk.services.budgets.model.Budget aws = sent.getValue().budget();
        assertThat(aws.budgetName()).isEqualTo("tcg-environment-env-1").isEqualTo(name);
        assertThat(aws.budgetType()).isEqualTo(BudgetType.COST);
        assertThat(aws.timeUnit()).isEqualTo(TimeUnit.MONTHLY);
        assertThat(aws.budgetLimit().amount()).isEqualByComparingTo("500.00");
        assertThat(aws.budgetLimit().unit()).isEqualTo("USD");
        assertThat(aws.costFilters()).containsEntry("TagKeyValue", List.of("user:tcg:environment$prod-01"));
        assertThat(sync.lastError("b-1")).isEmpty();
    }

    @Test
    void aMirroredBudgetIsUpdatedAndRecreatedWhenMissing() {
        AwsBudgetSyncService sync = new AwsBudgetSyncService(true, "123456789012", client);
        Budget team = budget(BudgetScopeType.TEAM, "Payments", "tcg-team-Payments");

        sync.upsert(team, "Payments");
        ArgumentCaptor<UpdateBudgetRequest> updated = ArgumentCaptor.forClass(UpdateBudgetRequest.class);
        verify(client).updateBudget(updated.capture());
        assertThat(updated.getValue().newBudget().costFilters()).containsEntry("TagKeyValue", List.of("user:tcg:team$Payments"));
        verify(client, never()).createBudget(any(CreateBudgetRequest.class));

        when(client.updateBudget(any(UpdateBudgetRequest.class))).thenThrow(NotFoundException.builder().message("gone").build());
        sync.upsert(team, "Payments");
        verify(client).createBudget(any(CreateBudgetRequest.class));
    }

    @Test
    void aRejectedCallIsRememberedAsAwsSyncFailed() {
        AwsBudgetSyncService sync = new AwsBudgetSyncService(true, "123456789012", client);
        when(client.createBudget(any(CreateBudgetRequest.class)))
                .thenThrow(BudgetsException.builder().message("AccessDenied").build());

        assertThat(sync.upsert(budget(BudgetScopeType.ENVIRONMENT, "env-1", null), "prod-01")).isNull();
        assertThat(sync.lastError("b-1")).hasValueSatisfying(m -> assertThat(m).startsWith("AWS sync failed"));
    }

    @Test
    void deletingRemovesTheAwsBudget() {
        AwsBudgetSyncService sync = new AwsBudgetSyncService(true, "123456789012", client);
        sync.delete("b-1", "tcg-environment-env-1");
        ArgumentCaptor<DeleteBudgetRequest> sent = ArgumentCaptor.forClass(DeleteBudgetRequest.class);
        verify(client).deleteBudget(sent.capture());
        assertThat(sent.getValue().budgetName()).isEqualTo("tcg-environment-env-1");
    }

    @Test
    void whenDisabledNothingIsCalled() {
        AwsBudgetSyncService off = new AwsBudgetSyncService(false, "123456789012", client);
        assertThat(off.upsert(budget(BudgetScopeType.ENVIRONMENT, "env-1", null), "prod-01")).isNull();
        off.delete("b-1", "tcg-environment-env-1");
        AwsBudgetSyncService noAccount = new AwsBudgetSyncService(true, "", client);
        assertThat(noAccount.isEnabled()).isFalse();
        noAccount.upsert(budget(BudgetScopeType.ENVIRONMENT, "env-1", null), "prod-01");
        verifyNoInteractions(client);
    }

    @Test
    void longNamesAreCutToTheAwsLimit() {
        Budget b = budget(BudgetScopeType.TEAM, "x".repeat(150), null);
        assertThat(AwsBudgetSyncService.awsName(b)).hasSize(AwsBudgetSyncService.MAX_NAME);
    }
}
