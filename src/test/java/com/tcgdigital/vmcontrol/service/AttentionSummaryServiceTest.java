package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.AttentionSummaryDTO;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.ApprovalPolicy;
import com.tcgdigital.vmcontrol.model.ApprovalRequest;
import com.tcgdigital.vmcontrol.model.Budget;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.model.ExecutionStatus;
import com.tcgdigital.vmcontrol.model.OperationExecution;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.ApprovalRequestRepository;
import com.tcgdigital.vmcontrol.repository.BudgetRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRequestRepository;
import com.tcgdigital.vmcontrol.repository.OperationExecutionRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** U05-T04: the administrator's "needs attention" summary, from seeded data (MySQL). */
@WithMockUser(username = "admin001@test.com")
class AttentionSummaryServiceTest extends AbstractIntegrationTest {

    @Autowired private AttentionSummaryService service;
    @Autowired private EnvironmentAccessRequestRepository accessRequests;
    @Autowired private ApprovalRequestRepository approvals;
    @Autowired private OperationExecutionRepository executions;
    @Autowired private BudgetRepository budgets;
    @Autowired private TeamRepository teams;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StateSyncService stateSync;

    @BeforeEach
    void clean() {
        accessRequests.deleteAll();
        approvals.deleteAll();
        jdbc.update("DELETE FROM operation_detail");
        executions.deleteAll();
        budgets.deleteAll();
        jdbc.update("UPDATE vm SET state_drift_detected = false, discovery_pending = false");
    }

    private void accessRequest(Environment env, Instant createdAt) {
        User requester = users.findById("user-001").orElseThrow();
        EnvironmentAccessRequest r = new EnvironmentAccessRequest(UUID.randomUUID().toString());
        r.setEnvironment(env);
        r.setScopeType(AccessScopeType.ENVIRONMENT);
        r.setScopeId(env.getEnvironmentId());
        r.setRequester(requester);
        r.setRequestedAccessLevel(AccessLevel.USER);
        r.setBusinessJustification("Regression run");
        r.setDurationDays(7);
        accessRequests.saveAndFlush(r);
        // created_at is a @CreationTimestamp: move it afterwards.
        jdbc.update("UPDATE environment_access_request SET created_at = ? WHERE request_id = ?", Timestamp.from(createdAt), r.getRequestId());
    }

    private void run(Environment env, ExecutionStatus status, Instant startedAt) {
        OperationExecution e = new OperationExecution();
        e.setExecutionId(UUID.randomUUID().toString());
        e.setEnvironment(env);
        e.setOperationType(OperationType.START);
        e.setStatus(status);
        e.setInitiatedByUserId("admin-001");
        e.setStartedAt(Timestamp.from(startedAt));
        e.setTotalTargets(1);
        executions.saveAndFlush(e);
    }

    private Budget budget(BudgetScopeType type, String scopeId, String amount, boolean enabled) {
        Budget b = new Budget();
        b.setBudgetId(UUID.randomUUID().toString());
        b.setScopeType(type);
        b.setScopeId(scopeId);
        b.setMonthlyAmount(new BigDecimal(amount));
        b.setForecastAlert(false);
        b.setEnabled(enabled);
        b.setCreatedByUserId("admin-001");
        return budgets.saveAndFlush(b);
    }

    @Test
    void withNothingWaitingEveryBlockIsZero() {
        AttentionSummaryDTO s = service.summary();

        assertThat(s.accessRequests()).isEqualTo(new AttentionSummaryDTO.Waiting(0, null));
        assertThat(s.approvals()).isEqualTo(new AttentionSummaryDTO.Waiting(0, null));
        assertThat(s.failedOperations().count24h()).isZero();
        assertThat(s.failedOperations().stuck()).isZero();
        assertThat(s.failedOperations().environmentNames()).isEmpty();
        assertThat(s.vmsToReview()).isEqualTo(new AttentionSummaryDTO.VmsToReview(0, 0));
        assertThat(s.budgets().nearLimit()).isZero();
        assertThat(s.monthPlan()).isNull();
        assertThat(s.health()).isNotNull();
        assertThat(s.generatedAt()).isNotNull();
    }

    @Test
    void pendingAccessRequestsCountWithTheOldestWait() {
        Environment env = newEnvironment("Requests");
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        accessRequest(env, now.minus(Duration.ofHours(3)));
        accessRequest(env, now.minus(Duration.ofDays(2)));
        accessRequest(env, now.minus(Duration.ofHours(20)));

        AttentionSummaryDTO.Waiting waiting = service.summary().accessRequests();

        assertThat(waiting.count()).isEqualTo(3);
        assertThat(waiting.oldestAt().toInstant()).isEqualTo(now.minus(Duration.ofDays(2)));
    }

    @Test
    void approvalsWaitingAreCounted() {
        Environment env = newEnvironment("Approvals");
        ApprovalRequest a = new ApprovalRequest();
        a.setRequestId(UUID.randomUUID().toString());
        a.setRequestType(ApprovalRequest.Type.START);
        a.setEnvironmentId(env.getEnvironmentId());
        a.setRequesterUserId("user-001");
        a.setPayload("{}");
        a.setApproverMode(ApprovalPolicy.ApproverMode.ANY);
        a.setCreatedAt(Timestamp.from(Instant.now().minus(Duration.ofHours(5))));
        a.setExpiresAt(Timestamp.from(Instant.now().plus(Duration.ofDays(1))));
        approvals.saveAndFlush(a);

        assertThat(service.summary().approvals().count()).isEqualTo(1);
    }

    @Test
    void failedRunsInTheLastDayNameTheirEnvironments() {
        Environment analytics = newEnvironment("Analytics QA");
        Environment crm = newEnvironment("CRM UAT");
        Instant now = Instant.now();
        run(analytics, ExecutionStatus.FAILED, now.minus(Duration.ofHours(2)));
        run(crm, ExecutionStatus.PARTIAL_SUCCESS, now.minus(Duration.ofHours(5)));
        run(crm, ExecutionStatus.FAILED, now.minus(Duration.ofDays(3)));        // too old
        run(crm, ExecutionStatus.COMPLETED, now.minus(Duration.ofHours(1)));    // not a failure
        run(analytics, ExecutionStatus.IN_PROGRESS, now.minus(Duration.ofHours(4))); // no heartbeat for hours: stuck

        AttentionSummaryDTO.FailedOperations failed = service.summary().failedOperations();

        assertThat(failed.count24h()).isEqualTo(2);
        assertThat(failed.environmentNames()).containsExactly("Analytics QA", "CRM UAT");
        assertThat(failed.stuck()).isEqualTo(1);
    }

    @Test
    void vmsToReviewCountDriftAndPendingAcrossEnvironments() {
        var group = newGroup(newEnvironment("Review"), "web");
        var drifted = newVm(group, "web-1", VmStatus.RUNNING);
        var pending = newVm(group, "web-2", VmStatus.STOPPED);
        var settled = newVm(group, "web-3", VmStatus.RUNNING);
        // U22-T12: VMs changed outside the platform in 24 h (two changes, one VM), not the flag that the
        // next sync clears; a VM settling after the platform's own start is not counted.
        stateSync.recordStateChange(drifted, VmStatus.STOPPED, VmStatus.RUNNING, "state_sync", null, null, "drift");
        stateSync.recordStateChange(drifted, VmStatus.RUNNING, VmStatus.STOPPED, "state_sync", null, null, "drift");
        stateSync.recordStateChange(settled, VmStatus.STARTING, VmStatus.RUNNING, "sync_settled", null, null, "settled");
        jdbc.update("UPDATE vm SET discovery_pending = true WHERE vm_id = ?", pending.getVmId());

        assertThat(service.summary().vmsToReview()).isEqualTo(new AttentionSummaryDTO.VmsToReview(1, 1));
        assertThat(service.summary().health().driftEvents24h()).isEqualTo(1);
    }

    @Test
    void thePlanCountsATeamBudgetOnceWithItsEnvironments() {
        Team team = new Team();
        team.setTeamId(UUID.randomUUID().toString());
        team.setName(TeamService.slug("CRM team " + UUID.randomUUID().toString().substring(0, 6)));
        team.setDisplayName("CRM team");
        team = teams.saveAndFlush(team);
        Environment crm = newEnvironment("CRM UAT");
        crm.setTeam(team);
        crm = environmentRepository.saveAndFlush(crm);
        Environment payments = newEnvironment("Payments Dev");

        budget(BudgetScopeType.TEAM, team.getTeamId(), "6000", true);
        budget(BudgetScopeType.ENVIRONMENT, crm.getEnvironmentId(), "1000", true);     // inside the team's budget
        budget(BudgetScopeType.ENVIRONMENT, payments.getEnvironmentId(), "4000", true);
        budget(BudgetScopeType.ENVIRONMENT, newEnvironment("Off").getEnvironmentId(), "9000", false); // switched off

        AttentionSummaryDTO.MonthPlan plan = service.summary().monthPlan();

        assertThat(plan.amount()).isEqualByComparingTo("10000");
        assertThat(plan.budgetCount()).isEqualTo(2);
    }
}
