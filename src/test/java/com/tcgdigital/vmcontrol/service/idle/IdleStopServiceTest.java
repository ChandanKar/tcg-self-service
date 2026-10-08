package com.tcgdigital.vmcontrol.service.idle;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.IdleStopEvent;
import com.tcgdigital.vmcontrol.model.IdleStopMode;
import com.tcgdigital.vmcontrol.model.IdleStopOutcome;
import com.tcgdigital.vmcontrol.model.IdleStopRule;
import com.tcgdigital.vmcontrol.model.IdleStopSnooze;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.IdleStopEventRepository;
import com.tcgdigital.vmcontrol.repository.IdleStopRuleRepository;
import com.tcgdigital.vmcontrol.repository.IdleStopSnoozeRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import com.tcgdigital.vmcontrol.scheduler.IdleStopScheduler;
import com.tcgdigital.vmcontrol.service.CloudProviderService;
import com.tcgdigital.vmcontrol.service.LockService;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Idle auto-stop end to end against MySQL (E16-T03): exclusions, dry run, enforcement.
 * Cloud calls are mocked; metric samples are seeded.
 */
class IdleStopServiceTest extends AbstractIntegrationTest {

    @Autowired private IdleStopService idleStopService;
    @Autowired private IdleStopRuleRepository rules;
    @Autowired private IdleStopEventRepository events;
    @Autowired private IdleStopSnoozeRepository snoozes;
    @Autowired private VmMetricSampleRepository samples;
    @Autowired private LockService lockService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationContext context;

    private Environment env;
    private User creator;
    private Vm app;
    private Vm db;
    private IdleStopRule rule;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Idle");
        app = newVm(newGroup(env, "app"), "app-1", VmStatus.RUNNING);
        db = newVm(newGroup(env, "db"), "db-1", VmStatus.RUNNING);
        creator = newUser("idle-owner-" + UUID.randomUUID() + "@example.com", false, true);
        grant(creator, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.ADMIN);
        quietFor(app, 70);
        quietFor(db, 70);
        when(awsCloudProviderService.stopVm(anyString(), anyString(), anyBoolean(), any())).thenReturn(
                CompletableFuture.completedFuture(CloudProviderService.VmOperationResult.success("r", VmStatus.STOPPED)));

        IdleStopRule r = new IdleStopRule();
        r.setRuleId(UUID.randomUUID().toString());
        r.setEnvironment(env);
        r.setDryRunStartedAt(Timestamp.from(Instant.now().minus(Duration.ofDays(20))));
        r.setCreatedByUserId(creator.getUserId());
        rule = rules.saveAndFlush(r);
    }

    /** One quiet 5-minute sample at a time for the last {@code minutes} minutes. */
    private void quietFor(Vm vm, int minutes) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        for (Instant t = now.minus(Duration.ofMinutes(minutes)); !t.isAfter(now); t = t.plus(Duration.ofMinutes(5))) {
            VmMetricSample s = new VmMetricSample();
            s.setMetricSampleId(UUID.randomUUID().toString());
            s.setVm(vm);
            s.setProvider(vm.getProvider());
            s.setProviderVmId(vm.getProviderVmId());
            s.setSampleTime(Timestamp.from(t));
            s.setPeriodSeconds(300);
            s.setCpuUtilization(new BigDecimal("1.0"));
            s.setNetworkInBytes(5_000L);
            s.setNetworkOutBytes(5_000L);
            samples.save(s);
        }
        samples.flush();
    }

    private void mode(IdleStopMode mode) {
        rule.setMode(mode);
        rule = rules.saveAndFlush(rule);
    }

    private List<IdleStopEvent> ruleEvents() {
        return events.findTop20ByEnvironmentIdOrderByEvaluatedAtDesc(env.getEnvironmentId());
    }

    private int stopExecutions() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM operation_execution WHERE environment_id = ?", Integer.class, env.getEnvironmentId());
        return n == null ? 0 : n;
    }

    @Test
    void theSchedulerIsAbsentWhileTheFeatureIsOff() {
        assertThat(context.getBeansOfType(IdleStopScheduler.class)).isEmpty();
    }

    @Test
    void aDryRunRecordsOneWouldStopPerEpisodeAndStopsNothing() {
        idleStopService.evaluateRule(rule.getRuleId());
        idleStopService.evaluateRule(rule.getRuleId());

        assertThat(ruleEvents()).singleElement().satisfies(e -> {
            assertThat(e.getOutcome()).isEqualTo(IdleStopOutcome.WOULD_STOP);
            assertThat(e.getIdleSince()).isNotNull();
            assertThat(e.getProjectedSavingPerHour()).isNotNull();
            assertThat(e.getEvidenceJson()).contains(app.getVmId(), db.getVmId(), "unpricedVmIds");
            assertThat(e.getExecutionId()).isNull();
        });
        assertThat(stopExecutions()).isZero();
    }

    @Test
    void enforcementStopsTheEnvironmentAndTellsItsMembers() {
        mode(IdleStopMode.ENFORCE);
        User member = newUser("idle-member-" + UUID.randomUUID() + "@example.com", false, false);
        grant(member, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);

        IdleStopEvent event = idleStopService.evaluateRule(rule.getRuleId()).orElseThrow();

        assertThat(event.getOutcome()).isEqualTo(IdleStopOutcome.STOPPED);
        assertThat(event.getExecutionId()).isNotNull();
        assertThat(jdbcTemplate.queryForObject("SELECT operation_type FROM operation_execution WHERE execution_id = ?",
                String.class, event.getExecutionId())).isEqualToIgnoringCase("STOP");
        awaitAsync(() -> {
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'IDLE_AUTO_STOPPED'",
                    Integer.class, member.getUserId())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action_type = 'IDLE_STOP_TRIGGERED' " +
                    "AND environment_id = ?", Integer.class, env.getEnvironmentId())).isEqualTo(1);
        });
    }

    @Test
    void aLockedEnvironmentIsSkippedWithTheHolderNamed() {
        mode(IdleStopMode.ENFORCE);
        User holder = newUser("holder-" + UUID.randomUUID() + "@example.com", false, false);
        grant(holder, AccessScopeType.ENVIRONMENT, env.getEnvironmentId(), AccessLevel.USER);
        lockService.acquireLock(env.getEnvironmentId(), holder.getUserId(), "working", null);

        IdleStopEvent event = idleStopService.evaluateRule(rule.getRuleId()).orElseThrow();

        assertThat(event.getOutcome()).isEqualTo(IdleStopOutcome.SKIPPED);
        assertThat(event.getReason()).isEqualTo("Locked by " + holder.getDisplayName());
        assertThat(stopExecutions()).isZero();
        // The same skip is not recorded again within the hour.
        assertThat(idleStopService.evaluateRule(rule.getRuleId())).isEmpty();
        assertThat(ruleEvents()).hasSize(1);
    }

    @Test
    void aRunningOperationIsSkipped() {
        jdbcTemplate.update("INSERT INTO operation_execution (execution_id, environment_id, operation_type, status, " +
                        "initiated_by_user_id, started_at, total_targets) VALUES (?, ?, 'start', 'in_progress', ?, ?, 1)",
                UUID.randomUUID().toString(), env.getEnvironmentId(), creator.getUserId(), Timestamp.from(Instant.now()));

        assertThat(idleStopService.evaluateRule(rule.getRuleId()).orElseThrow().getReason()).isEqualTo("Operation in progress");
    }

    @Test
    void aSnoozeAndProductionAreRespected() {
        IdleStopSnooze snooze = new IdleStopSnooze();
        snooze.setSnoozeId(UUID.randomUUID().toString());
        snooze.setEnvironmentId(env.getEnvironmentId());
        snooze.setSnoozedUntil(Timestamp.from(Instant.now().plus(Duration.ofHours(4))));
        snoozes.saveAndFlush(snooze);

        assertThat(idleStopService.evaluateRule(rule.getRuleId()).orElseThrow().getOutcome()).isEqualTo(IdleStopOutcome.SNOOZED);

        snoozes.deleteAll(snoozes.findActive(env.getEnvironmentId(), Timestamp.from(Instant.now())));
        jdbcTemplate.update("UPDATE environment SET is_production = TRUE WHERE environment_id = ?", env.getEnvironmentId());
        assertThat(idleStopService.evaluateRule(rule.getRuleId()).orElseThrow().getReason()).isEqualTo("Production environment");
    }

    @Test
    void aBusyVmMeansNoEvent() {
        VmMetricSample busy = new VmMetricSample();
        busy.setMetricSampleId(UUID.randomUUID().toString());
        busy.setVm(db);
        busy.setProvider(db.getProvider());
        busy.setProviderVmId(db.getProviderVmId());
        busy.setSampleTime(Timestamp.from(Instant.now().minus(Duration.ofMinutes(12))));
        busy.setPeriodSeconds(300);
        busy.setCpuUtilization(new BigDecimal("55"));
        samples.saveAndFlush(busy);

        assertThat(idleStopService.evaluateRule(rule.getRuleId())).isEmpty();
        assertThat(ruleEvents()).isEmpty();
    }

    @Test
    void anInactiveCreatorDisablesTheRule() {
        jdbcTemplate.update("UPDATE app_user SET is_active = FALSE WHERE user_id = ?", creator.getUserId());

        idleStopService.evaluateRule(rule.getRuleId());

        assertThat(rules.findById(rule.getRuleId()).orElseThrow().getEnabled()).isFalse();
        assertThat(stopExecutions()).isZero();
    }
}
