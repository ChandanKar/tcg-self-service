package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.AutomationRuleWindowDTO;
import com.tcgdigital.vmcontrol.dto.ScheduleSuggestionDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schedule suggestions from usage (E19-T05): the window proposal on synthetic activity, and the
 * end-to-end suggestion from state history and metrics (office hours, timezone shift, missing
 * metrics, too little history).
 */
class ScheduleSuggestionServiceTest extends AbstractIntegrationTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");
    /** "Now": Monday 2026-10-05 00:00 IST; the four weeks before it are the history. */
    private static final ZonedDateTime NOW = ZonedDateTime.of(LocalDate.of(2026, 10, 5), LocalTime.MIDNIGHT, KOLKATA);

    @Autowired private ScheduleSuggestionService service;
    @Autowired private PricingReferenceService pricing;
    @Autowired private VmInventorySnapshotRepository inventory;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Environment env;
    private Vm vm;

    @BeforeEach
    void setUp() {
        env = newEnvironment("Suggest");
        vm = newVm(newGroup(env, "app"), "app-1", VmStatus.RUNNING);
        jdbcTemplate.update("UPDATE vm SET created_at = ? WHERE vm_id = ?", Timestamp.valueOf("2026-01-01 00:00:00"), vm.getVmId());
        Object target = AopTestUtils.getTargetObject(service);
        ReflectionTestUtils.setField(target, "clock", Clock.fixed(NOW.toInstant(), KOLKATA));
    }

    // ------------------------------------------------------------------ proposal (pure)

    private static double[] officeHours(int fromHour, int toHourExclusive, DayOfWeek... days) {
        double[] share = new double[168];
        for (DayOfWeek d : days) {
            for (int h = fromHour; h < toHourExclusive; h++) {
                share[(d.getValue() - 1) * 24 + h] = 1.0;
            }
        }
        return share;
    }

    private static final DayOfWeek[] WEEKDAYS = {DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY};

    @Test
    void weekdayOfficeHoursBecomeOnePaddedWindow() {
        ScheduleSuggestionService.Proposal p = ScheduleSuggestionService.proposeWindows(officeHours(9, 18, WEEKDAYS));

        assertThat(p.windows()).singleElement().satisfies(w -> {
            assertThat(w.getDaysOfWeek()).containsExactly("MON", "TUE", "WED", "THU", "FRI");
            assertThat(w.getStartTime()).isEqualTo("08:30");
            assertThat(w.getStopTime()).isEqualTo("18:30");
        });
        assertThat(p.hoursPerWeek()).isEqualTo(50.0);
    }

    @Test
    void roundTheClockUseSuggestsNoStop() {
        double[] all = new double[168];
        Arrays.fill(all, 1.0);
        ScheduleSuggestionService.Proposal p = ScheduleSuggestionService.proposeWindows(all);

        assertThat(p.windows()).isEmpty();
        assertThat(p.alwaysOnDays()).hasSize(7);
        assertThat(p.hoursPerWeek()).isEqualTo(168.0);
    }

    @Test
    void sparseUseSuggestsNothingAndSingleHourGapsAreFilled() {
        double[] sparse = new double[168];
        Arrays.fill(sparse, 0.2); // below a quarter of the weeks everywhere
        assertThat(ScheduleSuggestionService.proposeWindows(sparse).windows()).isEmpty();

        double[] gap = officeHours(9, 11, DayOfWeek.SATURDAY);
        gap[(DayOfWeek.SATURDAY.getValue() - 1) * 24 + 12] = 1.0; // 11:00 unused, 12:00 used
        assertThat(ScheduleSuggestionService.proposeWindows(gap).windows()).singleElement().satisfies(w -> {
            assertThat(w.getDaysOfWeek()).containsExactly("SAT");
            assertThat(w.getStartTime()).isEqualTo("08:30");
            assertThat(w.getStopTime()).isEqualTo("13:30");
        });
    }

    @Test
    void differentDaysGetTheirOwnWindows() {
        double[] share = officeHours(9, 18, WEEKDAYS);
        double[] saturday = officeHours(10, 14, DayOfWeek.SATURDAY);
        for (int i = 0; i < 168; i++) {
            share[i] = Math.max(share[i], saturday[i]);
        }
        List<AutomationRuleWindowDTO> windows = ScheduleSuggestionService.proposeWindows(share).windows();
        assertThat(windows).hasSize(2);
        assertThat(windows.get(1).getDaysOfWeek()).containsExactly("SAT");
        assertThat(windows.get(1).getStartTime()).isEqualTo("09:30");
        assertThat(windows.get(1).getStopTime()).isEqualTo("14:30");
    }

    // ------------------------------------------------------------------ end to end

    /** The VM ran around the clock (a state from long before), with metrics every hour. */
    private void runningAllTheTimeWithOfficeHourMetrics() {
        history(NOW.minusWeeks(10).toInstant(), VmStatus.STOPPED, VmStatus.RUNNING);
        List<Object[]> rows = new ArrayList<>();
        for (ZonedDateTime t = NOW.minusWeeks(4); t.isBefore(NOW); t = t.plusHours(1)) {
            boolean office = t.getDayOfWeek().getValue() <= 5 && t.getHour() >= 9 && t.getHour() < 18;
            rows.add(new Object[] {UUID.randomUUID().toString(), vm.getVmId(), vm.getProviderVmId(),
                    Timestamp.from(t.toInstant()), office ? 40 : 1, office ? 50_000_000L : 1_000L});
        }
        jdbcTemplate.batchUpdate("INSERT INTO vm_metric_sample (metric_sample_id, vm_id, provider, provider_vm_id, sample_time, "
                + "period_seconds, cpu_utilization, network_in_bytes, network_out_bytes, created_at) "
                + "VALUES (?, ?, 'AWS', ?, ?, 3600, ?, ?, 0, NOW())", rows);
    }

    private void history(Instant at, VmStatus from, VmStatus to) {
        jdbcTemplate.update("INSERT INTO vm_state_history (history_id, vm_id, previous_status, new_status, change_source, created_at) "
                + "VALUES (?, ?, ?, ?, 'system', ?)", UUID.randomUUID().toString(), vm.getVmId(), from.name(), to.name(),
                Timestamp.from(at));
    }

    private void priced() {
        VmInventorySnapshot snapshot = new VmInventorySnapshot();
        snapshot.setInventoryId(UUID.randomUUID().toString());
        snapshot.setVm(vm);
        snapshot.setProvider(CloudProvider.AWS);
        snapshot.setProviderVmId(vm.getProviderVmId());
        snapshot.setInstanceType("t3.medium");
        snapshot.setLastRefreshedAt(Timestamp.from(Instant.now()));
        inventory.saveAndFlush(snapshot);
    }

    @Test
    void fourWeeksOfOfficeHourUseSuggestAWeekdayWindowAndItsSaving() {
        runningAllTheTimeWithOfficeHourMetrics();
        priced();

        ScheduleSuggestionDTO s = service.suggest(env.getEnvironmentId(), KOLKATA, 4);

        assertThat(s.windows()).singleElement().satisfies(w -> {
            assertThat(w.getDaysOfWeek()).containsExactly("MON", "TUE", "WED", "THU", "FRI");
            assertThat(w.getStartTime()).isEqualTo("08:30");
            assertThat(w.getStopTime()).isEqualTo("18:30");
        });
        assertThat(s.confidence()).isEqualTo("HIGH");
        assertThat(s.metricsCoveragePercent()).isEqualTo(100);
        assertThat(s.activityByHourOfWeek()).hasSize(168);
        assertThat(s.activityByHourOfWeek().get(9)).isEqualTo(1.0); // Monday 09:00
        assertThat(s.activityByHourOfWeek().get(5 * 24 + 9)).isEqualTo(0.0); // Saturday 09:00
        assertThat(s.currentRunningHoursPerWeek()).isEqualByComparingTo("168.0");
        assertThat(s.suggestedHoursPerWeek()).isEqualByComparingTo("50.0");
        BigDecimal rate = pricing.lookupHourlyRate("AWS", "t3.medium", vm.getRegion()).hourlyRate();
        assertThat(s.estimatedWeeklySaving()).isEqualByComparingTo(rate.multiply(BigDecimal.valueOf(118))
                .setScale(2, java.math.RoundingMode.HALF_UP));
        assertThat(s.unpricedVmCount()).isZero();
    }

    @Test
    void theSameUseSeenFromUtcShiftsTheWindow() {
        runningAllTheTimeWithOfficeHourMetrics();

        ScheduleSuggestionDTO s = service.suggest(env.getEnvironmentId(), ZoneId.of("UTC"), 4);

        // 09:00-18:00 IST is 03:30-12:30 UTC: hourly samples land at hh:30 UTC, hours 3..11.
        assertThat(s.windows()).singleElement().satisfies(w -> {
            assertThat(w.getStartTime()).isEqualTo("02:30");
            assertThat(w.getStopTime()).isEqualTo("12:30");
        });
        assertThat(s.unpricedVmCount()).isEqualTo(1); // no inventory type
    }

    @Test
    void withoutMetricsTheStateHistoryIsUsedWithLowConfidence() {
        history(NOW.minusWeeks(10).toInstant(), VmStatus.RUNNING, VmStatus.STOPPED);
        for (LocalDate day = NOW.minusWeeks(4).toLocalDate(); day.isBefore(NOW.toLocalDate()); day = day.plusDays(1)) {
            if (day.getDayOfWeek().getValue() <= 5) {
                history(day.atTime(9, 0).atZone(KOLKATA).toInstant(), VmStatus.STOPPED, VmStatus.RUNNING);
                history(day.atTime(18, 0).atZone(KOLKATA).toInstant(), VmStatus.RUNNING, VmStatus.STOPPED);
            }
        }

        ScheduleSuggestionDTO s = service.suggest(env.getEnvironmentId(), KOLKATA, 4);

        assertThat(s.confidence()).isEqualTo("LOW");
        assertThat(s.windows()).singleElement().satisfies(w -> {
            assertThat(w.getStartTime()).isEqualTo("08:30");
            assertThat(w.getStopTime()).isEqualTo("18:30");
        });
        assertThat(s.currentRunningHoursPerWeek()).isEqualByComparingTo("45.0");
        assertThat(s.estimatedWeeklySaving()).isEqualByComparingTo("0");
    }

    @Test
    void fiveDaysOfHistoryAreNotEnough() {
        history(NOW.minusDays(5).toInstant(), VmStatus.STOPPED, VmStatus.RUNNING);

        assertThatThrownBy(() -> service.suggest(env.getEnvironmentId(), KOLKATA, 4))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Not enough history (need 2 weeks)");
        assertThatThrownBy(() -> service.suggest(env.getEnvironmentId(), KOLKATA, 9))
                .hasMessage("weeks must be between 2 and 8");
    }
}
