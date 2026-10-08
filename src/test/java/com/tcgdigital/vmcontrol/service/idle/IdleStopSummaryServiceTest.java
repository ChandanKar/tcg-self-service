package com.tcgdigital.vmcontrol.service.idle;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.IdleStopEvent;
import com.tcgdigital.vmcontrol.model.IdleStopOutcome;
import com.tcgdigital.vmcontrol.model.IdleStopRule;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.IdleStopEventRepository;
import com.tcgdigital.vmcontrol.repository.IdleStopRuleRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import com.tcgdigital.vmcontrol.service.StateSyncService;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Would-have-saved and saved amounts (E16-T04): next-activity detection, the 24 h episode cap and
 * stopped hours. Fixed clock; against MySQL.
 */
class IdleStopSummaryServiceTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");

    @Autowired private IdleStopSummaryService summaryService;
    @Autowired private IdleStopRuleRepository rules;
    @Autowired private IdleStopEventRepository events;
    @Autowired private VmMetricSampleRepository samples;
    @Autowired private StateSyncService stateSyncService;

    private Environment env;
    private Vm vm;
    private IdleStopRule rule;

    @BeforeEach
    void setUp() {
        ((IdleStopSummaryService) AopTestUtils.getTargetObject(summaryService)).setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        env = newEnvironment("Savings");
        vm = newVm(newGroup(env, "app"), "app-1", VmStatus.RUNNING);
        User owner = newUser("savings-" + UUID.randomUUID() + "@example.com", true, false);
        IdleStopRule r = new IdleStopRule();
        r.setRuleId(UUID.randomUUID().toString());
        r.setEnvironment(env);
        r.setCreatedByUserId(owner.getUserId());
        rule = rules.saveAndFlush(r);
    }

    @AfterEach
    void realClock() {
        ((IdleStopSummaryService) AopTestUtils.getTargetObject(summaryService)).setClock(Clock.systemUTC());
    }

    private IdleStopEvent event(IdleStopOutcome outcome, Instant at, String perHour) {
        IdleStopEvent e = new IdleStopEvent();
        e.setEventId(UUID.randomUUID().toString());
        e.setRuleId(rule.getRuleId());
        e.setEnvironmentId(env.getEnvironmentId());
        e.setEvaluatedAt(Timestamp.from(at));
        e.setOutcome(outcome);
        e.setIdleSince(Timestamp.from(at.minus(Duration.ofHours(1))));
        e.setProjectedSavingPerHour(new BigDecimal(perHour));
        e.setEvidenceJson("{\"vms\":[{\"vmId\":\"" + vm.getVmId() + "\"}]}");
        return events.saveAndFlush(e);
    }

    private void busyAt(Instant at) {
        VmMetricSample s = new VmMetricSample();
        s.setMetricSampleId(UUID.randomUUID().toString());
        s.setVm(vm);
        s.setProvider(vm.getProvider());
        s.setProviderVmId(vm.getProviderVmId());
        s.setSampleTime(Timestamp.from(at));
        s.setPeriodSeconds(300);
        s.setCpuUtilization(new BigDecimal("60"));
        samples.saveAndFlush(s);
    }

    @Test
    void threeEpisodesEachFollowedBySixQuietHoursAtFiftyCentsAreNineDollars() {
        for (int daysAgo : new int[]{10, 7, 3}) {
            Instant at = NOW.minus(Duration.ofDays(daysAgo));
            event(IdleStopOutcome.WOULD_STOP, at, "0.50");
            busyAt(at.plus(Duration.ofHours(6)));
        }

        IdleStopSummaryService.Summary summary = summaryService.summarize(env.getEnvironmentId(), 14);

        assertThat(summary.wouldStopEpisodes()).isEqualTo(3);
        assertThat(summary.wouldHaveSaved()).isEqualByComparingTo("9.00");
    }

    @Test
    void anEpisodeWithoutActivityIsCappedAtTwentyFourHours() {
        event(IdleStopOutcome.WOULD_STOP, NOW.minus(Duration.ofDays(5)), "0.50");

        assertThat(summaryService.summarize(env.getEnvironmentId(), 14).wouldHaveSaved()).isEqualByComparingTo("12.00");
    }

    @Test
    void aStateChangeEndsTheEpisodeAndOlderEventsAreOutsideTheWindow() {
        Instant at = NOW.minus(Duration.ofDays(2));
        event(IdleStopOutcome.WOULD_STOP, at, "1.00");
        event(IdleStopOutcome.WOULD_STOP, NOW.minus(Duration.ofDays(20)), "1.00"); // outside 14 days
        stateSyncService.recordStateChange(vmRepository.getReferenceById(vm.getVmId()), VmStatus.RUNNING, VmStatus.STOPPED,
                "user_action", null, null, "stopped by hand");
        // recordStateChange stamps "now" (real time): move it to 2 h after the episode.
        jdbcUpdate("UPDATE vm_state_history SET created_at = ? WHERE vm_id = ?", Timestamp.from(at.plus(Duration.ofHours(2))), vm.getVmId());

        IdleStopSummaryService.Summary summary = summaryService.summarize(env.getEnvironmentId(), 14);

        assertThat(summary.wouldStopEpisodes()).isEqualTo(1);
        assertThat(summary.wouldHaveSaved()).isEqualByComparingTo("2.00");
    }

    @Test
    void savedHoursRunUntilTheVmsStartAgain() {
        Instant at = NOW.minus(Duration.ofDays(1));
        IdleStopEvent stopped = event(IdleStopOutcome.STOPPED, at, "0.40");
        stateSyncService.recordStateChange(vmRepository.getReferenceById(vm.getVmId()), VmStatus.STOPPED, VmStatus.RUNNING,
                "user_action", null, null, "started");
        jdbcUpdate("UPDATE vm_state_history SET created_at = ? WHERE vm_id = ?", Timestamp.from(at.plus(Duration.ofHours(3))), vm.getVmId());

        IdleStopSummaryService.Summary summary = summaryService.summarize(env.getEnvironmentId(), 14);

        assertThat(summary.stoppedCount()).isEqualTo(1);
        assertThat(summary.savedEstimate()).isEqualByComparingTo("1.20");
        assertThat(summary.stoppedSavings()).singleElement().satisfies(entry -> {
            assertThat(entry.idleStopEventId()).isEqualTo(stopped.getEventId());
            assertThat(entry.hours()).isEqualByComparingTo("3");
        });
    }

    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private void jdbcUpdate(String sql, Object... args) {
        jdbcTemplate.update(sql, args);
    }
}
